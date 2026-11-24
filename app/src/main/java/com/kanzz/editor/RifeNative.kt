package com.kanzz.editor

/** Native RIFE v4.6 engine. Not yet connected to the Motion Blur workflow. */
object RifeNative {
    init { System.loadLibrary("rifejni") }
    external fun gpuInfo(): String
    /** 0=GPU normal, 1=GPU safe, 2=CPU safe FP32, 3=CPU fast FP16 */
    external fun initModel(modelDir: String, mode: Int): Boolean
    external fun interpolate(input0: String, input1: String, timestep: Float, output: String): Boolean
    external fun lastInfo(): String
    external fun cutCount(): Int
    external fun dupCount(): Int
    external fun cpuInfo(): String
    external fun badCount(): Int
    external fun goodCount(): Int
    external fun release()
}
