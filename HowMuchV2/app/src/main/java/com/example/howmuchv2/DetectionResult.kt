package com.example.howmuchv2 // Pastikan ini sesuai dengan package Anda

import android.graphics.RectF

/**
 * Data class sederhana untuk menyimpan hasil deteksi dari model.
 * @param boundingBox Kotak pembatas di sekitar objek yang terdeteksi.
 * @param text Label kelas dari objek (misalnya, "100000").
 * @param confidence Skor kepercayaan dari deteksi.
 */
data class DetectionResult(val boundingBox: RectF, val text: String, val confidence: Float)
