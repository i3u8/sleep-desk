package com.i3u8.sleepdesk

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.i3u8.sleepdesk.ui.HistoryFragment
import com.i3u8.sleepdesk.ui.HomeFragment

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val nav = findViewById<BottomNavigationView>(R.id.bottomNav)
        if (savedInstanceState == null) {
            switchTo(HomeFragment(), "home")
            nav.selectedItemId = R.id.nav_home
        }
        nav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> switchTo(HomeFragment(), "home")
                R.id.nav_history -> switchTo(HistoryFragment(), "history")
                else -> return@setOnItemSelectedListener false
            }
            true
        }
    }

    private fun switchTo(fragment: Fragment, tag: String) {
        val existing = supportFragmentManager.findFragmentByTag(tag)
        supportFragmentManager.beginTransaction().apply {
            supportFragmentManager.fragments.forEach { hide(it) }
            if (existing != null) {
                show(existing)
            } else {
                add(R.id.fragmentContainer, fragment, tag)
            }
            commit()
        }
    }
}
