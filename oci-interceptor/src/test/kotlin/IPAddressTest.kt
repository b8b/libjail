import inet.ipaddr.AddressStringParameters
import inet.ipaddr.IPAddress
import inet.ipaddr.IPAddressString
import inet.ipaddr.IPAddressStringParameters
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.cikit.oci.cni.AddResult
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class IPAddressTest {

    @Test
    fun testBasic() {
        val x = IPAddressString("1.2.3.4-5/22")
        val address = x.address
        println("isSingleNetwork: " + address.isSingleNetwork)
        println("isIPAddress: " + address.isIPAddress)
        println("isMultiple: " + address.isMultiple)
        println("isSinglePrefixBlock: " + address.isSinglePrefixBlock)
        println("isPrefixed: " + address.isPrefixed)
        println(address.toCanonicalString())
    }

    @Test
    fun testSimpleIp() {
        val x = IPAddressString(
            "1.2.3.0/32",
            IPAddressStringParameters.Builder()
                .allowAll(false)
                .setRangeOptions(AddressStringParameters.RangeParameters.NO_RANGE)
                .toParams()
        ).address
        assertNotNull(x)
        assertTrue(x.isIPAddress)
        assertFalse(x.isMultiple)
        assertTrue(x.isPrefixed)
        assertTrue(x.isSingleNetwork)
        assertEquals(BigInteger.ONE, x.prefixCount)
        assertTrue(x.segments.last().isZero)
        println(x.isIPAddress)
        for (ip in listOf("1.2.3.4", "1.2.3.0")) {
            val x = IPAddressString(ip).address
            assertTrue(x.isIPAddress)
            assertFalse(x.isPrefixed)
            assertFalse(x.isMultiple)
            assertFalse(x.isSinglePrefixBlock)
            assertTrue(x.isSingleNetwork)
        }
    }

    @Test
    fun testAddResult() {
        val addResult = AddResult(
            ips = listOf(
                AddResult.Ip(
                    version = "4",
                    address = "10.88.0.60/16",
                    gateway = "10.88.0.1",
                )
            ),
            routes = listOf(
                AddResult.Route(
                    dst = "0.0.0.0/0"
                )
            )
        )
        val ifConfig = addResult.toIFConfig(
            buildJsonObject {
                put("isGateway", true)
                put("isDefaultGateway", true)
            }
        )
        println(ifConfig)
    }

    @Test
    fun testAdjustPrefixLength() {
        val x = IPAddressString("1.2.3.4/16").address
        val y = x.withoutPrefixLength().adjustPrefixLength(32)
        assertEquals(IPAddressString("1.2.3.4/32").address, y)
        assertEquals(32, IPAddress.getBitCount(y.ipVersion))
    }

    @Test
    fun testContains() {
        val x = IPAddressString("1.2.3.4/16").address
        val y = IPAddressString("1.2.3.1").address
        assertEquals(16, x.prefixLength)
        val range = x.toPrefixBlock()
        println(range)
        assertTrue(range.contains(y))
    }
}