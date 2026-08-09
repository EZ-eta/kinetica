package com.kinetica.keyboard.layout

data class KeyboardLayout(
    val name: String,
    val locale: String,
    val keys: List<Key>,
)
