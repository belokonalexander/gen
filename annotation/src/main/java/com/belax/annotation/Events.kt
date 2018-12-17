package com.belax.annotation

interface Event <T> {
    fun get(): T?
}

open class SimpleEvent<T>(protected val item: T) : Event<T> {
    override fun get(): T? = item
}

class SingleEvent<T>(item: T) : SimpleEvent<T>(item) {
    var alreadyUsed = false
        private set

    override fun get(): T? {
        if (alreadyUsed) return null
        alreadyUsed = true
        return super.get()
    }
}

