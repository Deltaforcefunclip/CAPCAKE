#include <jni.h>
#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>
#include <android/log.h>

#include "cpu.h"
#include "gpu.h"
#include "rife.h"

#define STB_IMAGE_IMPLEMENTATION
#include "stb_image.h"
#define STB_IMAGE_WRITE_IMPLEMENTATION
#include "stb_image_write.h"

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "RifeJNI", __VA_ARGS__)

static RIFE* g_rife = nullptr;
static int g_bad = 0;   // jumlah frame hasil AI yang terdeteksi rusak
static int g_good = 0;  // jumlah frame hasil AI yang tampak wajar
static int g_mode = -1; // mode yang sedang dimuat
static int g_last_w = 0, g_last_h = 0;
static double g_last_err = 0.0;
static double g_last_blk = 0.0;
static double g_last_hist = 0.0;
static int g_dups = 0;  // frame yang hampir sama persis (AI dilewati)
static int g_threads = 0, g_big = 0, g_cores = 0;
static int g_cuts = 0;  // jumlah pergantian adegan (video yang dipotong lalu disambung) yang terdeteksi

// Beda sebaran warna (histogram) antar dua frame, 0 = sama, 1 = sama sekali berbeda.
// Gerakan kamera hampir tidak mengubah sebaran warna; pergantian adegan mengubahnya.
static double hist_diff(const unsigned char* a, const unsigned char* b, int w, int h)
{
    long ha[48] = {0}, hb[48] = {0};
    long n = 0;
    for (int y = 0; y < h; y += 4) {
        for (int x = 0; x < w; x += 4) {
            size_t i = ((size_t)y * w + x) * 3;
            for (int c = 0; c < 3; c++) {
                ha[c * 16 + (a[i + c] >> 4)]++;
                hb[c * 16 + (b[i + c] >> 4)]++;
            }
            n++;
        }
    }
    if (n == 0) return 0.0;
    double d = 0.0;
    for (int k = 0; k < 48; k++) d += std::abs((double)ha[k] - (double)hb[k]);
    return d / (3.0 * 2.0 * (double)n);
}

// Beda rata-rata antar blok besar (32x18). Gerakan biasa hanya mengubah sedikit rata-rata blok,
// sedangkan pergantian adegan (potongan) mengubahnya banyak.
static double block_diff(const unsigned char* a, const unsigned char* b, int w, int h)
{
    const int GX = 32, GY = 18;
    double total = 0.0;
    int cells = 0;
    for (int gy = 0; gy < GY; gy++) {
        int y0 = gy * h / GY, y1 = (gy + 1) * h / GY;
        for (int gx = 0; gx < GX; gx++) {
            int x0 = gx * w / GX, x1 = (gx + 1) * w / GX;
            long long sa = 0, sb = 0;
            long n = 0;
            for (int y = y0; y < y1; y += 4) {
                for (int x = x0; x < x1; x += 4) {
                    size_t i = ((size_t)y * w + x) * 3;
                    sa += a[i] + a[i + 1] + a[i + 2];
                    sb += b[i] + b[i + 1] + b[i + 2];
                    n++;
                }
            }
            if (n > 0) {
                total += std::abs((double)sa - (double)sb) / (3.0 * (double)n);
                cells++;
            }
        }
    }
    return cells ? total / cells : 0.0;
}

// Salin gambar RGB ke kanvas lebih besar (pw x ph), sisanya diisi dengan mengulang piksel tepi.
static void make_padded(const unsigned char* src, int w, int h, int pw, int ph, std::vector<unsigned char>& dst)
{
    dst.resize((size_t)pw * ph * 3);
    for (int y = 0; y < ph; y++) {
        int sy = y < h ? y : h - 1;
        const unsigned char* srow = src + (size_t)sy * w * 3;
        unsigned char* drow = dst.data() + (size_t)y * pw * 3;
        memcpy(drow, srow, (size_t)w * 3);
        for (int x = w; x < pw; x++) {
            drow[x * 3 + 0] = srow[(w - 1) * 3 + 0];
            drow[x * 3 + 1] = srow[(w - 1) * 3 + 1];
            drow[x * 3 + 2] = srow[(w - 1) * 3 + 2];
        }
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_kanzz_editor_RifeNative_gpuInfo(JNIEnv* env, jobject) {
    ncnn::create_gpu_instance();
    int n = ncnn::get_gpu_count();
    std::string s = "jumlah GPU=" + std::to_string(n);
    for (int i = 0; i < n; i++) {
        s += "; gpu" + std::to_string(i) + "=" + ncnn::get_gpu_info(i).device_name();
    }
    s += "; gpu_bawaan=" + std::to_string(ncnn::get_default_gpu_index());

    // Tes kecil: apakah kompilasi shader (glslang) bisa jalan di HP ini?
    ncnn::Option opt;
    opt.use_vulkan_compute = true;
    std::vector<uint32_t> spirv;
    static const char test_src[] =
        "#version 450\nlayout (local_size_x = 1, local_size_y = 1, local_size_z = 1) in;\nvoid main() {}\n";
    // Tes 1: tanpa karakter NUL di ujung. Tes 2: dengan NUL (seperti header lama kita).
    int r = ncnn::compile_spirv_module(test_src, (int)strlen(test_src), opt, spirv);
    std::vector<uint32_t> spirv2;
    int r2 = ncnn::compile_spirv_module(test_src, (int)sizeof(test_src), opt, spirv2);
    s += "; tes_shader=" + std::to_string(r) + " ukuran=" + std::to_string(spirv.size()) +
         "; tes_shader_nul=" + std::to_string(r2);
    return env->NewStringUTF(s.c_str());
}

// mode: 0 = GPU normal, 1 = GPU aman (tanpa fp16), 2 = CPU
extern "C" JNIEXPORT jboolean JNICALL
Java_com_kanzz_editor_RifeNative_initModel(JNIEnv* env, jobject, jstring modelDir, jint mode) {
    g_bad = 0;
    g_good = 0;
    g_cuts = 0;
    g_dups = 0;
    if (g_rife && g_mode == mode) return JNI_TRUE;
    if (g_rife) {  // mode berubah: muat ulang mesin AI dengan pengaturan baru
        delete g_rife;
        g_rife = nullptr;
    }
    unsetenv("RIFE_SAFE");

    int gpu = -1;
    int threads = 1;
    if (mode == 2 || mode == 3) {
        // CPU: pakai inti besar (cepat) saja. Inti kecil membuat semua benang menunggu yang paling lambat.
        ncnn::set_cpu_powersave(2);
        g_cores = ncnn::get_cpu_count();
        g_big = ncnn::get_big_cpu_count();
        threads = g_big >= 2 ? g_big : g_cores;
        ncnn::set_omp_num_threads(threads);
        g_threads = threads;
        if (mode == 2) setenv("RIFE_SAFE", "1", 1);  // mode aman: hitung penuh (fp32)
    } else {
        ncnn::create_gpu_instance();
        gpu = ncnn::get_default_gpu_index();
        if (mode == 1) setenv("RIFE_SAFE", "1", 1);
    }

    // Sesuaikan argumen dengan versi rife.h di repo kamu.
    g_rife = new RIFE(gpu, false, false, false, threads, false, /*rife_v4=*/true);

    const char* dir = env->GetStringUTFChars(modelDir, nullptr);
    int ret = g_rife->load(std::string(dir));
    env->ReleaseStringUTFChars(modelDir, dir);

    if (ret != 0) {
        LOG("load model gagal: %d", ret);
        delete g_rife;
        g_rife = nullptr;
        return JNI_FALSE;
    }
    g_mode = mode;
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_kanzz_editor_RifeNative_interpolate(JNIEnv* env, jobject,
        jstring in0, jstring in1, jfloat timestep, jstring out) {
    if (!g_rife) return JNI_FALSE;

    const char* p0 = env->GetStringUTFChars(in0, nullptr);
    const char* p1 = env->GetStringUTFChars(in1, nullptr);
    const char* po = env->GetStringUTFChars(out, nullptr);

    int w0, h0, c0, w1, h1, c1;
    unsigned char* d0 = stbi_load(p0, &w0, &h0, &c0, 3);
    unsigned char* d1 = stbi_load(p1, &w1, &h1, &c1, 3);

    bool ok = false;
    double bd = 0.0, hd = 0.0, md = 1.0;
    if (d0 && d1 && w0 == w1 && h0 == h1) {
        bd = block_diff(d0, d1, w0, h0);
        hd = hist_diff(d0, d1, w0, h0);
        {
            size_t tot = (size_t)w0 * h0 * 3;
            long long sd = 0;
            long c2 = 0;
            for (size_t i = 0; i < tot; i += 22) {
                sd += std::abs((int)d0[i] - (int)d1[i]);
                c2++;
            }
            md = c2 ? (double)sd / (double)c2 : 0.0;
        }
        g_last_blk = bd;
        g_last_hist = hd;
    }
    bool is_dup = (md < 0.35);  // dua frame hampir sama persis (mis. frame ganda): tak perlu AI
    // Batas dikalibrasi dari video game bergerak cepat: gerakan/efek terbesar di sana hanya mencapai
    // blok=33 dan warna=0,28. Potongan adegan sungguhan harus jauh di atas itu.
    bool is_cut = !is_dup && ((bd > 60.0) || (bd > 12.0 && hd > 0.50));
    if (d0 && d1 && w0 == w1 && h0 == h1 && is_dup) {
        g_dups++;
        ok = stbi_write_jpg(po, w0, h0, 3, d0, 95) != 0;
    } else if (d0 && d1 && w0 == w1 && h0 == h1 && is_cut) {
        // Pergantian adegan (video pernah dipotong lalu disambung): jangan buat frame "campuran"
        // dua gambar yang tidak berhubungan. Pakai saja frame terdekat.
        g_cuts++;
        const unsigned char* src = (timestep > 0.5f) ? d1 : d0;
        ok = stbi_write_jpg(po, w0, h0, 3, src, 95) != 0;
    } else if (d0 && d1 && w0 == w1 && h0 == h1) {
        // Lebar/tinggi dibulatkan ke kelipatan 32 dengan mengulang piksel tepi, supaya AI tidak
        // bergantung pada penanganan ukuran ganjil. Hasil dipotong lagi ke ukuran asli.
        int pw = (w0 + 31) / 32 * 32;
        int ph = (h0 + 31) / 32 * 32;
        std::vector<unsigned char> q0, q1;
        make_padded(d0, w0, h0, pw, ph, q0);
        make_padded(d1, w0, h0, pw, ph, q1);

        ncnn::Mat m0(pw, ph, (void*)q0.data(), (size_t)3, 3);
        ncnn::Mat m1(pw, ph, (void*)q1.data(), (size_t)3, 3);
        ncnn::Mat mo(pw, ph, (size_t)3, 3);

        g_rife->process(m0, m1, timestep, mo);

        std::vector<unsigned char> res((size_t)w0 * h0 * 3);
        const unsigned char* o = (const unsigned char*)mo.data;
        for (int y = 0; y < h0; y++) {
            memcpy(res.data() + (size_t)y * w0 * 3, o + (size_t)y * pw * 3, (size_t)w0 * 3);
        }

        // Pemeriksa: hasil AI yang wajar pasti mirip salah satu dari: frame sebelum, frame sesudah,
        // atau campuran keduanya. Hasil rusak (garis-garis) tidak mirip ketiganya.
        size_t total = (size_t)w0 * h0 * 3;
        long long sB = 0, s0 = 0, s1 = 0;
        long cnt = 0;
        for (size_t i = 0; i < total; i += 22) {
            int a = d0[i], b = d1[i], c = res[i];
            sB += std::abs(c - (a + b) / 2);
            s0 += std::abs(c - a);
            s1 += std::abs(c - b);
            cnt++;
        }
        double eB = cnt ? (double)sB / (double)cnt : 0.0;
        double e0 = cnt ? (double)s0 / (double)cnt : 0.0;
        double e1 = cnt ? (double)s1 / (double)cnt : 0.0;
        double err = std::min(eB, std::min(e0, e1));
        g_last_w = w0;
        g_last_h = h0;
        g_last_err = err;
        if (err > 38.0) {
            g_bad++;
            for (size_t i = 0; i < total; i++) res[i] = (unsigned char)(((int)d0[i] + (int)d1[i]) / 2);
        } else {
            g_good++;
        }
        ok = stbi_write_jpg(po, w0, h0, 3, res.data(), 95) != 0;
    } else {
        LOG("frame gagal dibaca / ukuran beda");
    }

    if (d0) stbi_image_free(d0);
    if (d1) stbi_image_free(d1);
    env->ReleaseStringUTFChars(in0, p0);
    env->ReleaseStringUTFChars(in1, p1);
    env->ReleaseStringUTFChars(out, po);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_kanzz_editor_RifeNative_lastInfo(JNIEnv* env, jobject) {
    std::string s = "w=" + std::to_string(g_last_w) + " h=" + std::to_string(g_last_h) +
                    " selisih=" + std::to_string(g_last_err) +
                    " blok=" + std::to_string(g_last_blk) +
                    " warna=" + std::to_string(g_last_hist);
    return env->NewStringUTF(s.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_kanzz_editor_RifeNative_cpuInfo(JNIEnv* env, jobject) {
    std::string s = "inti=" + std::to_string(g_cores) + " besar=" + std::to_string(g_big) +
                    " benang=" + std::to_string(g_threads);
    return env->NewStringUTF(s.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_kanzz_editor_RifeNative_dupCount(JNIEnv*, jobject) { return g_dups; }

extern "C" JNIEXPORT jint JNICALL
Java_com_kanzz_editor_RifeNative_cutCount(JNIEnv*, jobject) { return g_cuts; }

extern "C" JNIEXPORT jint JNICALL
Java_com_kanzz_editor_RifeNative_badCount(JNIEnv*, jobject) { return g_bad; }

extern "C" JNIEXPORT jint JNICALL
Java_com_kanzz_editor_RifeNative_goodCount(JNIEnv*, jobject) { return g_good; }

extern "C" JNIEXPORT void JNICALL
Java_com_kanzz_editor_RifeNative_release(JNIEnv*, jobject) {
    delete g_rife;
    g_rife = nullptr;
    g_mode = -1;
    ncnn::destroy_gpu_instance();
}