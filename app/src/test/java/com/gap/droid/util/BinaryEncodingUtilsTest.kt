package com.gapmesh.droid.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BinaryEncodingUtilsTest {

    @Test
    fun testDataFromHexString_Valid() {
        val hex = "48656c6c6f" // "Hello"
        val expected = "Hello".toByteArray()
        assertArrayEquals(expected, hex.dataFromHexString())
    }

    @Test
    fun testDataFromHexString_WithPrefix() {
        val hex = "0x48656c6c6f"
        val expected = "Hello".toByteArray()
        assertArrayEquals(expected, hex.dataFromHexString())
    }
    
    @Test
    fun testDataFromHexString_WithPrefixUpper() {
        val hex = "0X48656c6c6f"
        val expected = "Hello".toByteArray()
        assertArrayEquals(expected, hex.dataFromHexString())
    }
    
    @Test
    fun testDataFromHexString_WithWhitespace() {
        val hex = "48 65 6c 6c 6f\n"
        val expected = "Hello".toByteArray()
        assertArrayEquals(expected, hex.dataFromHexString())
    }
    
    @Test
    fun testDataFromHexString_MixedCase() {
        val hex = "48656C6C6F"
        val expected = "Hello".toByteArray()
        assertArrayEquals(expected, hex.dataFromHexString())
    }
    
    @Test
    fun testDataFromHexString_InvalidLength() {
        val hex = "1"
        assertNull(hex.dataFromHexString())
    }
    
    @Test
    fun testDataFromHexString_InvalidChar() {
        val hex = "GG"
        assertNull(hex.dataFromHexString())
    }
}
