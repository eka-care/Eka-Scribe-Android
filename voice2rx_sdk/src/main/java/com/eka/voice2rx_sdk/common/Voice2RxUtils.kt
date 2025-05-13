package com.eka.voice2rx_sdk.common

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

object Voice2RxUtils {
    fun isRecordAudioPermissionGranted(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }
    fun getCurrentUTCEpochMillis(): Long {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC")).timeInMillis
    }
    fun getCurrentDateInYYMMDD(): String {
        val date = Date()
        val formatter = SimpleDateFormat("yyMMdd", Locale.getDefault())
        return formatter.format(date)
    }

    fun getTimeStampInYYMMDD(utc : Long) : String {
        val date = Date(utc)
        val formatter = SimpleDateFormat("yyMMdd", Locale.getDefault())
        return formatter.format(date)
    }

    fun calculateDuration(start: Long, end: Long): Double {
        val durationMillis = end - start
        val durationSeconds = durationMillis / 1000.0
        return String.format("%.2f", durationSeconds).toDouble()
    }

    fun convertTimestampToISO8601(timestamp: Long): String {
        val date = Date(timestamp)
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.getDefault())
        sdf.timeZone = TimeZone.getTimeZone("UTC") // Set to UTC
        return sdf.format(date)
    }

    fun generateNewSessionId() : String {
        return UUID.randomUUID().toString() + "_" + System.currentTimeMillis()
    }

    fun getFullRecordingFileName(sessionId : String) : String {
        return "${sessionId}_Full_Recording.m4a_"
    }

    fun getFilePath(context: Context, sessionId : String) : String {
        return context.applicationContext.filesDir.absolutePath + "/" + getFullRecordingFileName(sessionId)
    }

    fun convertBase64IntoString(base64: String?): String {
        if (base64 == null) {
            return ""
        }
        val decodedBytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
        return String(decodedBytes)
//        val decodedBytes = Base64.decode(base64, Base64.DEFAULT)
//        return String(decodedBytes, Charsets.UTF_8)
    }

    fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork) ?: return false
            return when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->    true
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->   true
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->   true
                else ->     false
            }
        }
        else {
            if (connectivityManager.activeNetworkInfo != null && connectivityManager.activeNetworkInfo!!.isConnectedOrConnecting) {
                return true
            }
        }
        return false
    }
}