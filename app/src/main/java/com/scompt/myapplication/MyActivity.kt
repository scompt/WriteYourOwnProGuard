package com.scompt.myapplication

import android.support.v7.app.AppCompatActivity
import android.os.Bundle

class MyActivity : AppCompatActivity() {

    var count = 5

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val myTestThing = MyTestThing()
        getSomething()
    }

    private fun getSomething() = 5
}
