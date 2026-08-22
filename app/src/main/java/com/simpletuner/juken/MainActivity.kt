package com.simpletuner.juken

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.viewModels
import androidx.fragment.app.Fragment

class MainActivity : AppCompatActivity() {

    val viewModel: EcuViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (savedInstanceState == null) {
            showFragment(ConnectFragment())
        }

        findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNav)
            .setOnItemSelectedListener { item ->
                val fragment: Fragment = when (item.itemId) {
                    R.id.nav_connect -> ConnectFragment()
                    R.id.nav_dashboard -> DashboardFragment()
                    R.id.nav_maps -> MapsFragment()
                    R.id.nav_logging -> LoggingFragment()
                    else -> ConnectFragment()
                }
                showFragment(fragment)
                true
            }
    }

    private fun showFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }
}
