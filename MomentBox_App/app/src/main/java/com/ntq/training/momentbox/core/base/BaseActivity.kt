package com.ntq.training.momentbox.core.base

import android.os.Bundle
import android.view.LayoutInflater
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.viewbinding.ViewBinding

abstract class BaseActivity<VB : ViewBinding>(
    private val bindingInflater: (LayoutInflater) -> VB,
    private val applyDefaultEdgeToEdge: Boolean = true,
) : AppCompatActivity() {
    private var _binding: VB? = null
    protected val binding: VB
        get() = _binding ?: throw IllegalStateException("Binding accessed outside lifecycle: ${_binding == null}")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (applyDefaultEdgeToEdge) enableEdgeToEdge()
        _binding = bindingInflater(layoutInflater)
        setContentView(binding.root)
        setupInsets()
        initViews()
        observeData()
    }

    protected open fun setupInsets(){}
    protected open fun initViews(){}
    protected open fun observeData(){}

    override fun onDestroy() {
        super.onDestroy()
        _binding = null
    }
}
