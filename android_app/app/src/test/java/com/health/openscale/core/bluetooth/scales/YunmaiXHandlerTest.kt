/*
 * openScale
 * Copyright (C) 2026 openScale contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.core.bluetooth.scales

import android.util.SparseArray
import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.service.ScannedDeviceInfo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Tests for [YunmaiXAdv] frame parsing and [YunmaiXHandler.supportFor] matching.
 *
 * All fixture frames are real advertisements captured from a Yunmai X (YMBS-M268)
 * at MAC ED:68:01:8B:5D:45 with nRF Connect (2026-07-08): a full measurement session
 * going live (state 01) → stable (02) → final incl. impedance (03). Android parses
 * the first two AD bytes (45 5D) as manufacturer id 0x5D45; the payloads below are
 * the remaining 14 bytes.
 *
 * Robolectric is required for android.util.SparseArray in ScannedDeviceInfo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class YunmaiXHandlerTest {

    private val MFR_ID = 0x5D45
    private val ADDRESS = "ED:68:01:8B:5D:45"

    // Captured payloads (14 bytes each, after Android's manufacturer-id split)
    private val FRAME_IDLE = "8B0168ED0B741704010000000005" // state 01, 0.00 kg
    private val FRAME_LIVE = "8B0168ED0B741704011B8F000091" // state 01, 70.55 kg
    private val FRAME_STABLE = "8B0168ED0B741704021BA80000B5" // state 02, 70.80 kg
    private val FRAME_FINAL = "8B0168ED0B741704031BA80213A5" // state 03, 70.80 kg, 531 Ω

    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun uuid16(short: Int): UUID =
        UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", short))

    private fun device(
        payloadHex: String? = null,
        mfrId: Int = MFR_ID,
        address: String = ADDRESS,
        vararg services: Int
    ) = ScannedDeviceInfo(
        name = "",
        address = address,
        rssi = -50,
        serviceUuids = services.map { uuid16(it) },
        manufacturerData = payloadHex?.let {
            SparseArray<ByteArray>().apply { put(mfrId, bytes(it)) }
        },
    )

    // --- Frame parsing --------------------------------------------------------

    @Test
    fun `parses live frame`() {
        val f = YunmaiXAdv.parse(MFR_ID, bytes(FRAME_LIVE), ADDRESS)!!
        assertThat(f.state).isEqualTo(YunmaiXAdv.STATE_LIVE)
        assertThat(f.weightKg).isWithin(1e-3f).of(70.55f)
        assertThat(f.impedanceOhm).isEqualTo(0)
    }

    @Test
    fun `parses stable frame`() {
        val f = YunmaiXAdv.parse(MFR_ID, bytes(FRAME_STABLE), ADDRESS)!!
        assertThat(f.state).isEqualTo(YunmaiXAdv.STATE_STABLE)
        assertThat(f.weightKg).isWithin(1e-3f).of(70.80f)
    }

    @Test
    fun `parses final frame with impedance`() {
        val f = YunmaiXAdv.parse(MFR_ID, bytes(FRAME_FINAL), ADDRESS)!!
        assertThat(f.state).isEqualTo(YunmaiXAdv.STATE_FINAL)
        assertThat(f.weightKg).isWithin(1e-3f).of(70.80f)
        assertThat(f.impedanceOhm).isEqualTo(531)
    }

    @Test
    fun `parses idle frame as zero weight`() {
        val f = YunmaiXAdv.parse(MFR_ID, bytes(FRAME_IDLE), ADDRESS)!!
        assertThat(f.state).isEqualTo(YunmaiXAdv.STATE_LIVE)
        assertThat(f.weightKg).isEqualTo(0f)
    }

    @Test
    fun `rejects corrupted checksum`() {
        val corrupted = bytes(FRAME_FINAL).also { it[9] = 0x1C } // weight byte changed, checksum stale
        assertThat(YunmaiXAdv.parse(MFR_ID, corrupted, ADDRESS)).isNull()
    }

    @Test
    fun `rejects wrong signature`() {
        val wrongSig = bytes(FRAME_FINAL).also { it[5] = 0x75 }
        assertThat(YunmaiXAdv.parse(MFR_ID, wrongSig, ADDRESS)).isNull()
    }

    @Test
    fun `rejects frame whose MAC echo does not match the device address`() {
        assertThat(YunmaiXAdv.parse(MFR_ID, bytes(FRAME_FINAL), "AA:BB:CC:DD:EE:FF")).isNull()
    }

    @Test
    fun `rejects wrong payload size`() {
        assertThat(YunmaiXAdv.parse(MFR_ID, bytes(FRAME_FINAL).copyOf(13), ADDRESS)).isNull()
    }

    // --- Device matching --------------------------------------------------------

    @Test
    fun `claims nameless device advertising a valid measurement frame`() {
        assertThat(YunmaiXHandler().supportFor(device(FRAME_FINAL))).isNotNull()
    }

    @Test
    fun `claims nameless device advertising an idle frame`() {
        assertThat(YunmaiXHandler().supportFor(device(FRAME_IDLE))).isNotNull()
    }

    @Test
    fun `claims saved-device snapshot via service uuid 0x1320`() {
        // Snapshots carry no manufacturer data, only the advertised service UUIDs.
        assertThat(YunmaiXHandler().supportFor(device(null, MFR_ID, ADDRESS, 0x1320))).isNotNull()
    }

    @Test
    fun `does not claim device with foreign manufacturer data`() {
        // Same length, invalid signature/checksum (e.g. a wearable's frame)
        assertThat(YunmaiXHandler().supportFor(device("0102030405060708090A0B0C0D0E"))).isNull()
    }

    @Test
    fun `does not claim device without manufacturer data or 0x1320 service`() {
        assertThat(YunmaiXHandler().supportFor(device(null, MFR_ID, ADDRESS, 0x180D, 0x180F))).isNull()
    }

    @Test
    fun `does not claim valid frame relocated to another device address`() {
        // Replayed/echoed frame on a different MAC must not match.
        assertThat(YunmaiXHandler().supportFor(device(FRAME_FINAL, address = "AA:BB:CC:DD:EE:FF"))).isNull()
    }
}
