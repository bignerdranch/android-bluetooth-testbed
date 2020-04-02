package com.bignerdranch.android.bluetoothtestbed

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.DataBindingUtil
import com.bignerdranch.android.bluetoothtestbed.client.ClientActivity
import com.bignerdranch.android.bluetoothtestbed.databinding.ActivityMainBinding
import com.bignerdranch.android.bluetoothtestbed.server.ServerActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        with(DataBindingUtil.setContentView(this, R.layout.activity_main)
                as ActivityMainBinding) {
            launchServerButton.setOnClickListener {
                startActivity(Intent(this@MainActivity,
                        ServerActivity::class.java))
            }
            launchClientButton.setOnClickListener {
                startActivity(Intent(this@MainActivity,
                        ClientActivity::class.java))
            }
        }
    }
}