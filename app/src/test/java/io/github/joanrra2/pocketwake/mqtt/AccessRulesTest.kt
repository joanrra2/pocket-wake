package io.github.joanrra2.pocketwake.mqtt

import io.github.joanrra2.pocketwake.core.Profile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * "Test connection" against a local broker with access rules, to check that refused
 * subscriptions (SUBACK 0x80) are reported. Opt-in; needs a plain-TCP broker that publishes
 * a retained `sinkhole/blocking` and refuses `sinkhole/blocking` and `sinkhole/stats` to the
 * user "limited" (for example an aedes script with authorizeSubscribe):
 *   POCKETWAKE_ACL_BROKER=127.0.0.1:1883 ./gradlew testDebugUnitTest --tests '*AccessRulesTest*'
 */
class AccessRulesTest {
    private val broker = System.getenv("POCKETWAKE_ACL_BROKER")

    @Before fun optIn() = assumeTrue(!broker.isNullOrBlank())

    private fun profile(user: String) = Profile(
        host = broker!!.substringBefore(':'),
        port = broker.substringAfter(':').toInt(),
        tls = false,
        username = user,
        password = "x",
    )

    @Test fun `full login sees the blocking switch`() = runBlocking {
        val r = ConnectionTest.run(profile("pocket-phone"), "pocketwake-acl-test")
        assertNull(r.error)
        assertEquals(emptyList<String>(), r.denied)
        assertEquals(true, r.blocking)
    }

    @Test fun `refused topics are reported`() = runBlocking {
        val r = ConnectionTest.run(profile("limited"), "pocketwake-acl-test")
        assertNull(r.error)
        assertEquals(listOf("sinkhole/blocking", "sinkhole/stats"), r.denied)
        assertEquals(emptyList<String>(), r.deniedRequired("sinkhole"))
        assertEquals(listOf("sinkhole/blocking", "sinkhole/stats"), r.deniedOptional("sinkhole"))
        assertNull(r.blocking)
    }
}
