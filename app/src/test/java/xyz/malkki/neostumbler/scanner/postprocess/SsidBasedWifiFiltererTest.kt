package xyz.malkki.neostumbler.scanner.postprocess

import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.malkki.neostumbler.domain.Position
import xyz.malkki.neostumbler.domain.Position.Source
import xyz.malkki.neostumbler.domain.WifiAccessPoint
import xyz.malkki.neostumbler.scanner.data.ReportData

class SsidBasedWifiFiltererTest {
    @Test
    fun `No access points are filtered without a filter`() {
        val ssidBasedWifiFilterer = SsidBasedWifiFilterer(emptyList())

        val report =
            ReportData(
                position =
                    Position(
                        latitude = 45.51156,
                        longitude = 12.213415,
                        source = Source.GPS,
                        timestamp = 0,
                    ),
                cellTowers = emptyList(),
                wifiAccessPoints =
                    listOf(
                        WifiAccessPoint(
                            macAddress = "01:01:01:01:01:01",
                            ssid = "test_ssid_1",
                            timestamp = 0,
                        ),
                        WifiAccessPoint(
                            macAddress = "02:02:02:02:02:02",
                            ssid = "test_ssid_2",
                            timestamp = 0,
                        ),
                    ),
                bluetoothBeacons = emptyList(),
            )

        val filteredReport = ssidBasedWifiFilterer.postProcessReport(report)

        assertEquals(2, filteredReport?.wifiAccessPoints?.size)
    }

    @Test
    fun `Access points with SSID on the filter list get filtered`() {
        val ssidBasedWifiFilterer = SsidBasedWifiFilterer(listOf("test_ssid"))

        val report =
            ReportData(
                position =
                    Position(
                        latitude = 45.51156,
                        longitude = 12.213415,
                        source = Source.GPS,
                        timestamp = 0,
                    ),
                cellTowers = emptyList(),
                wifiAccessPoints =
                    listOf(
                        WifiAccessPoint(
                            macAddress = "01:01:01:01:01:01",
                            ssid = "test_ssid_1",
                            timestamp = 0,
                        ),
                        WifiAccessPoint(
                            macAddress = "02:02:02:02:02:02",
                            ssid = "test_ssid_2",
                            timestamp = 0,
                        ),
                    ),
                bluetoothBeacons = emptyList(),
            )

        val filteredReport = ssidBasedWifiFilterer.postProcessReport(report)

        assertEquals(0, filteredReport?.wifiAccessPoints?.size)
    }
}
