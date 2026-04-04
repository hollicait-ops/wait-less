package com.streambridge

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.FragmentActivity

class MainActivity : FragmentActivity() {

    private lateinit var etHostIp: EditText
    private lateinit var btnConnect: Button
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etHostIp = findViewById(R.id.et_host_ip)
        btnConnect = findViewById(R.id.btn_connect)
        tvStatus = findViewById(R.id.tv_status)

        val prefs = getPreferences(MODE_PRIVATE)
        etHostIp.setText(prefs.getString("last_ip", ""))

        btnConnect.setOnClickListener {
            val ip = etHostIp.text.toString().trim()
            if (ip.isNotEmpty()) {
                prefs.edit().putString("last_ip", ip).apply()
                val intent = Intent(this, StreamActivity::class.java)
                intent.putExtra(StreamActivity.EXTRA_HOST_IP, ip)
                startActivity(intent)
            }
        }

        checkWifiBand()
    }

    private fun checkWifiBand() {
        val freq = getWifiFrequencyMhz()
        tvStatus.text = when (wifiBandFromFrequency(freq)) {
            WifiBand.BAND_2_4_GHZ ->
                "Warning: connected to 2.4 GHz WiFi. Switch to 5 GHz for best performance."
            WifiBand.UNKNOWN ->
                "Warning: could not determine WiFi band. Ensure both devices are on 5 GHz."
            WifiBand.BAND_5_GHZ -> ""
        }
    }

    private fun getWifiFrequencyMhz(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            val wifiInfo = caps?.transportInfo as? WifiInfo
            if (wifiInfo != null) return wifiInfo.frequency
        }
        @Suppress("DEPRECATION")
        return (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .connectionInfo.frequency
    }
}
