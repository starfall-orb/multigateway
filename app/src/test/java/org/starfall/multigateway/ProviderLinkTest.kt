package org.starfall.multigateway

import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.multigateway.data.model.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProviderLinkTest {
    @Test fun defaultsAndEncodedValuesPopulateProviderCredentials() {
        val uri = Uri.Builder().scheme("multigateway").authority("provider")
            .appendQueryParameter("name", "Gateway ảnh & video")
            .appendQueryParameter("url", "https://example.com/v1?route=a&mode=b")
            .appendQueryParameter("key", "test+a/b=c&d%value")
            .appendQueryParameter("icon_url", "https://example.com/icon.png?size=128&theme=dark").build()
        val provider = parseProviderLink(uri)
        assertEquals(ProviderType.OPENAI, provider.type)
        assertEquals(AuthMethod.PLATFORM_DEFAULT, provider.auth.method)
        assertEquals("Gateway ảnh & video", provider.name)
        assertEquals("https://example.com/v1?route=a&mode=b", provider.baseUrl)
        assertEquals("test+a/b=c&d%value", provider.auth.token)
        assertEquals("https://example.com/icon.png?size=128&theme=dark", provider.icon)
        assertEquals("Bearer test+a/b=c&d%value", provider.auth.requestHeaders(provider.type)["Authorization"])
        assertNotEquals(provider.id, parseProviderLink(uri).id)
    }

    @Test fun explicitTypeAndAuthUseTheirWireSemantics() {
        val google = parseProviderLink(Uri.parse("multigateway://provider?type=gemini&auth=platform&key=test"))
        assertEquals(ProviderType.GOOGLE, google.type)
        assertEquals(ProviderType.GOOGLE.defaultBaseUrl, google.baseUrl)
        assertEquals(mapOf("x-goog-api-key" to "test"), google.auth.requestHeaders(google.type))
        val bearer = parseProviderLink(Uri.parse("multigateway://provider?type=openai_responses&auth=bearer-token&key=test"))
        assertEquals(ProviderType.OPENAI_RESPONSES, bearer.type)
        assertEquals(AuthMethod.BEARER_TOKEN, bearer.auth.method)
        assertEquals("Authorization", bearer.auth.key)
        assertEquals("test", bearer.auth.token)
        val query = parseProviderLink(Uri.parse("multigateway://provider?auth=query&key=test"))
        assertEquals(AuthMethod.QUERY_PARAM, query.auth.method)
        assertEquals("key", query.auth.key)
        assertEquals("test", query.auth.value)
    }

    @Test fun blankOptionsUseDefaultsAndIncompleteProvidersCanBeEdited() {
        val provider = parseProviderLink(Uri.parse("multigateway://provider?type=&name=&url=&auth=&key="))
        assertEquals(ProviderType.OPENAI, provider.type)
        assertEquals(AuthMethod.PLATFORM_DEFAULT, provider.auth.method)
        assertEquals("New provider", provider.name)
        assertEquals("", provider.baseUrl)
        assertEquals("", provider.auth.token)
    }

    @Test fun invalidLinksAreRejectedWithoutLeakingCredentialValues() {
        listOf(
            "https://provider?key=secret", "multigateway://oauth?key=secret",
            "multigateway://provider?type=secret", "multigateway://provider?auth=secret",
            "multigateway://provider?url=file%3A%2F%2Fsecret&key=secret",
            "multigateway://provider?icon_url=content%3A%2F%2Fsecret&key=secret"
        ).forEach { link ->
            val error = runCatching { parseProviderLink(Uri.parse(link)) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertFalse(error!!.message.orEmpty().contains("secret"))
        }
    }
}
