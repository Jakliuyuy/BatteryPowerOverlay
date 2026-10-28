package com.jakliuyuy.batterypower

import com.jakliuyuy.batterypower.battery.BatteryReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class BatteryPowerCalculationTest {

    @Test
    fun testPowerFormatting_highPowerCharging() {
        val sample = BatteryReader.Sample(
            powerW = 45.36,
            currentMa = 5000,
            voltageMv = 9072,
            level = 80,
            tempC = 34.5,
            status = "Charging",
            isCharging = true,
            source = "root"
        )
        // 功率 >= 10W 格式化为 1 位小数
        assertEquals("+45.4W", sample.powerText())
    }

    @Test
    fun testPowerFormatting_lowPowerCharging() {
        val sample = BatteryReader.Sample(
            powerW = 4.256,
            currentMa = 1000,
            voltageMv = 4256,
            level = 50,
            tempC = 28.0,
            status = "Charging",
            isCharging = true,
            source = "root"
        )
        // 功率 < 10W 格式化为 2 位小数
        assertEquals("+4.26W", sample.powerText())
    }

    @Test
    fun testPowerFormatting_discharging() {
        val sample = BatteryReader.Sample(
            powerW = -3.85,
            currentMa = -950,
            voltageMv = 4050,
            level = 65,
            tempC = 30.2,
            status = "Discharging",
            isCharging = false,
            source = "root"
        )
        assertEquals("-3.85W", sample.powerText())
    }

    @Test
    fun testCurrentNormalization_microAmpsVsMilliAmps() {
        // 当读到的 current_now > 50,000 时，必定是 μA（500,000 μA = 500 mA）
        val rawMicroAmps = 1_850_000L
        val normalizedMa1 = if (abs(rawMicroAmps) > 50_000) (rawMicroAmps / 1000).toInt() else rawMicroAmps.toInt()
        assertEquals(1850, normalizedMa1)

        // 当读到的 current_now <= 50,000 时（如一加 Ace 6T 直接返回 mA），直接采用
        val rawMilliAmps = 2450L
        val normalizedMa2 = if (abs(rawMilliAmps) > 50_000) (rawMilliAmps / 1000).toInt() else rawMilliAmps.toInt()
        assertEquals(2450, normalizedMa2)
    }

    @Test
    fun testDoubleCellMultiplication() {
        val currentMa = 2500
        val voltageMv = 4200
        val baseW = (currentMa / 1000.0) * (voltageMv / 1000.0)
        val doubleCellW = baseW * 2.0
        assertEquals(10.5, baseW, 0.001)
        assertEquals(21.0, doubleCellW, 0.001)
    }

    @Test
    fun testPolarityInversion() {
        var isChargingNormally = true
        var invert = true
        var finalIsCharging = if (invert) !isChargingNormally else isChargingNormally
        assertFalse(finalIsCharging)

        invert = false
        finalIsCharging = if (invert) !isChargingNormally else isChargingNormally
        assertTrue(finalIsCharging)
    }
}
