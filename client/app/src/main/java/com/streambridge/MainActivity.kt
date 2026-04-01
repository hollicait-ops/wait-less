package com.streambridge

import android.content.Intent
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

        btnConnect.setOnClickListener {
            val ip = etHostIp.text.toString().trim()
            if (ip.isNotEmpty()) {
                val intent = Intent(this, StreamActivity::class.java)
                intent.putExtra(StreamActivity.EXTRA_HOST_IP, ip)
                startActivity(intent)
            }
        }
    }
}
