package com.belax.timerapp

import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import com.belax.annotation.Event
import com.belax.annotation.Single
import com.belax.annotation.SingleEvent
import com.belax.annotation.VMState
import io.reactivex.Observable
import io.reactivex.subjects.BehaviorSubject

class MainActivity : AppCompatActivity() {


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

    }
}

@VMState
data class Example(
        @Single val stringState: Map<String, Int> = mapOf("1" to 1),
        val longState: MutableSet<Int>?,
        val longStat2e: Int
)


interface A1 {
    fun stringStateChanges(): Observable<String>
}


abstract class A() : Generated_ExampleObservable {
    init {
        //BehaviorSubject.createDefault<java.util.Set<Integer>?>(null)
    }
}


interface ExampleObservable {
    fun stringStateObservable(): List<String>
}