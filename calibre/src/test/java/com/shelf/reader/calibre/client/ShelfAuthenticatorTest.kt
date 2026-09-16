package com.shelf.reader.calibre.client

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfAuthenticatorTest {

    private fun response(challenge: String?, path: String = "/opds"): Response {
        val builder = Response.Builder()
            .request(Request.Builder().url("http://host:8080$path").build())
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
        if (challenge != null) builder.header("WWW-Authenticate", challenge)
        return builder.build()
    }

    @Test
    fun `answers a basic challenge`() {
        val request = ShelfAuthenticator("alice", "secret").authenticate(null, response("Basic realm=\"calibre\""))
        assertNotNull(request)
        assertEquals("Basic YWxpY2U6c2VjcmV0", request!!.header("Authorization"))
    }

    @Test
    fun `answers a digest challenge with a well-formed header`() {
        val request = ShelfAuthenticator("alice", "secret").authenticate(
            null,
            response(
                "Digest realm=\"calibre\", nonce=\"abc123\", qop=\"auth\", algorithm=MD5, opaque=\"op1\""
            )
        )
        assertNotNull(request)
        val header = request!!.header("Authorization")!!
        assertTrue(header.startsWith("Digest "))
        assertTrue(header.contains("username=\"alice\""))
        assertTrue(header.contains("realm=\"calibre\""))
        assertTrue(header.contains("nonce=\"abc123\""))
        assertTrue(header.contains("qop=auth"))
        assertTrue(header.contains("opaque=\"op1\""))
        val digestValue = Regex("response=\"([0-9a-f]{32})\"").find(header)
        assertNotNull("digest response must be a 32 char md5 hex", digestValue)
    }

    @Test
    fun `does not answer when no credentials are configured`() {
        assertNull(ShelfAuthenticator("", "").authenticate(null, response("Basic realm=\"x\"")))
    }

    @Test
    fun `does not answer an unknown challenge`() {
        assertNull(ShelfAuthenticator("alice", "secret").authenticate(null, response("Bearer realm=\"x\"")))
    }
}