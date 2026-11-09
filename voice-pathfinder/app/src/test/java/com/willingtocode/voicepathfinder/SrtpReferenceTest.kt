package com.willingtocode.voicepathfinder

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The only test here that compares against something other than this implementation.
 *
 * The expected bytes were produced by pion/srtp v3 for the same master key, sequence,
 * timestamp, SSRC and payload. Everything below the packet layer had been checked against
 * RFC 3711's own numbers and still put something on the wire that a standard receiver
 * dropped, because the tag covered the packet but not the rollover counter. Only an
 * independent implementation catches that class of mistake.
 */
class SrtpReferenceTest {

    private fun hex(text: String): ByteArray =
        text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.hex(): String = joinToString("") { "%02X".format(it) }

    @Test
    fun `a protected packet matches an independent implementation`() {
        val key = SrtpKey.fromInline(
            hex("E1F97A0D3E018BE0D64FA32C06DE4139") + hex("0EC675AD498AFEEBB6960B3AABE6"),
        )
        val payload = ByteArray(160) { ((it * 7) and 0xFF).toByte() }
        val packet = Rtp.build(
            payloadType = 0,
            marker = false,
            sequence = 1234,
            timestamp = 160000,
            ssrc = 0x0BADC0DEL,
            payload = payload,
        )
        val expected =
            "800004D2000271000BADC0DE621A3EE15F21D6EE12396C5524828EB2E3142C3F" +
            "330B6289C69B1BEE6BB42CFD86328ED88ACF5A7488029E648DF8D1C7034F783E" +
            "F1BDA9E9CC4980293C8E50676179705D815F90B5CBC61E2BE56373BC419D802D" +
            "0975EA37A6F30787D25714E12C9A4DB6906B28C9020FA89FFDA7E023E2FCFC01" +
            "0ED117CD5E58FD4F9342639AEB81F014A568BF90BC0EB274491F7A9C90395E2C" +
            "D09ADC166440EA447EA297052219EF4E8D0741FC02DD"

        assertEquals(expected, SrtpContext(key).protect(packet).hex())
    }
}
