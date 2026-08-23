package com.simpletuner.juken

import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
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
                    R.id.nav_maps -> MapsListFragment()
                    R.id.nav_autotune -> AutoTuneFragment()
                    R.id.nav_more -> MoreFragment()
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

    /** Dipanggil dari MapsListFragment saat user pilih salah satu map. */
    fun openMapDetail(spec: MapSpec) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, MapDetailFragment.newInstance(spec))
            .addToBackStack("mapDetail")
            .commit()
    }
}
