package com.bignerdranch.android.bluetoothtestbed;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.databinding.DataBindingUtil;

import com.bignerdranch.android.bluetoothtestbed.client.ClientActivity;
import com.bignerdranch.android.bluetoothtestbed.databinding.ActivityMainBinding;
import com.bignerdranch.android.bluetoothtestbed.server.ServerActivity;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityMainBinding binding = DataBindingUtil.setContentView(this, R.layout.activity_main);

        binding.launchServerButton.setOnClickListener(v -> startActivity(new Intent(MainActivity.this,
                ServerActivity.class)));
        binding.launchClientButton.setOnClickListener(v -> startActivity(new Intent(MainActivity.this,
                ClientActivity.class)));
    }
}
