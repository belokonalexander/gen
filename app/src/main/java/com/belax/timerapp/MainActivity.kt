package com.belax.timerapp

import android.os.Bundle
import android.widget.Toast
import android.widget.Toast.LENGTH_SHORT
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProviders
import com.belax.annotation.Config
import com.belax.annotation.VMState
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import kotlinx.android.synthetic.main.activity_main.*


class MainActivity : AppCompatActivity() {

    val composite = CompositeDisposable()
    lateinit var viewModel: MainViewModel

    fun Disposable.to(composite: CompositeDisposable) = composite.add(composite).let { this }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val vmFactory = ViewModelFactory(savedInstanceState)
        viewModel = ViewModelProviders.of(this, vmFactory).get(MainViewModel::class.java)

        viewModel.nameChanges().subscribe { event -> event.get()?.let { textMain.text = it } }
                .to(composite)
        viewModel.toastChanges().subscribe { event -> event.get()?.let { Toast.makeText(this, it, LENGTH_SHORT).show() }}
                .to(composite)


        buttonName.setOnClickListener { viewModel.applyName(editText.text.toString()) }
        buttonTost.setOnClickListener { viewModel.toast(editText.text.toString()) }

    }

    override fun onSaveInstanceState(outState: Bundle?) {
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        super.onDestroy()
        composite.clear()
    }
}

@VMState
data class MainState(
        val city: String = "Moscow",
        @Config(isRetain = true)
        val name: String = "Sasha",
        @Config(isSingle = true)
        val toast: String? = null
)

class ViewModelFactory(bundle: Bundle?) : ViewModelProvider.Factory {
    override fun <T : ViewModel?> create(modelClass: Class<T>): T {
        return when (modelClass) {
            MainViewModel::class.java -> MainViewModel(MainStateDelegate()) as T
            else -> ViewModelProvider.NewInstanceFactory().create(modelClass)
        }
    }
}

class MainViewModel(private val delegate: MainStateDelegate) : ViewModel(), MainStateClient by delegate {
    fun applyName(name: String) {
        delegate.pushName(name)
    }

    fun toast(toast: String) {
        delegate.pushToast(toast)
    }
}