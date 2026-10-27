package dev.phoenix.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MinecraftHowSignInPolicyTest {
    @Test fun acceptsOnlyTlsSiteAndKnownProviderAuthPaths() {
        assertTrue(MinecraftHowSignInPolicy.isAllowedTopLevelUrl("https://minecraft.how/login"))
        assertTrue(MinecraftHowSignInPolicy.isAllowedTopLevelUrl("https://minecraft.how/auth/google/callback?code=example"))
        assertTrue(MinecraftHowSignInPolicy.isAllowedTopLevelUrl("https://discord.com/oauth2/authorize?client_id=x"))
        assertTrue(MinecraftHowSignInPolicy.isAllowedTopLevelUrl("https://discord.com/api/oauth2/authorize?client_id=x"))
        assertEquals(MinecraftHowSignInPolicy.Provider.DISCORD, MinecraftHowSignInPolicy.providerFor("https://discord.com/api/oauth2/authorize"))
        assertTrue(MinecraftHowSignInPolicy.isAllowedTopLevelUrl("https://login.microsoftonline.com/common/oauth2/v2.0/authorize"))
        assertTrue(MinecraftHowSignInPolicy.isAllowedTopLevelUrl("https://id.twitch.tv/oauth2/authorize"))
        assertTrue(MinecraftHowSignInPolicy.isAllowedTopLevelUrl("https://steamcommunity.com/openid/login"))
        assertTrue(MinecraftHowSignInPolicy.isAllowedTopLevelUrl("https://m.facebook.com/unified/login_via/app/?next=example"))
    }

    @Test fun rejectsCleartextSpoofedHostsNonstandardPortsAndUnverifiedProviderPaths() {
        assertFalse(MinecraftHowSignInPolicy.isAllowedTopLevelUrl("http://minecraft.how/login"))
        assertFalse(MinecraftHowSignInPolicy.isAllowedTopLevelUrl("https://minecraft.how.evil.example/login"))
        assertFalse(MinecraftHowSignInPolicy.isAllowedTopLevelUrl("https://minecraft.how:444/login"))
        assertFalse(MinecraftHowSignInPolicy.isAllowedTopLevelUrl("https://user@minecraft.how/login"))
        assertFalse(MinecraftHowSignInPolicy.isAllowedTopLevelUrl("https://accounts.google.com.evil.example/o/oauth2/v2/auth"))
        assertFalse(MinecraftHowSignInPolicy.isAllowedTopLevelUrl("https://accounts.google.com/drive/"))
        assertFalse(MinecraftHowSignInPolicy.isAllowedTopLevelUrl("https://accounts.google.com/o/oauth2/v2/auth?client_id=x"))
        assertFalse(MinecraftHowSignInPolicy.isAllowedTopLevelUrl("https://discord.com/channels/@me"))
        assertFalse(MinecraftHowSignInPolicy.isAllowedTopLevelUrl("https://discord.com/api/users/@me"))
        assertFalse(MinecraftHowSignInPolicy.isAllowedTopLevelUrl("https://discord.com/oauth2/authorize/extra"))
        assertFalse(MinecraftHowSignInPolicy.isAllowedTopLevelUrl("https://m.facebook.com/unified/other/"))
        assertFalse(MinecraftHowSignInPolicy.isAllowedTopLevelUrl("https://m.facebook.com/unified/login_via/app/extra"))
        assertFalse(MinecraftHowSignInPolicy.isAllowedTopLevelUrl("intent://minecraft.how/login"))
        assertFalse(MinecraftHowSignInPolicy.isAllowedTopLevelUrl("file:///data/local/tmp/login.html"))
    }

    @Test fun dashboardSuccessRequiresExactMinecraftHowHttpsRoute() {
        assertTrue(MinecraftHowSignInPolicy.isDnsDashboardUrl("https://minecraft.how/dashboard/dns"))
        assertTrue(MinecraftHowSignInPolicy.isDnsDashboardUrl("https://minecraft.how/dashboard/dns/?page=1"))
        assertFalse(MinecraftHowSignInPolicy.isDnsDashboardUrl("http://minecraft.how/dashboard/dns"))
        assertFalse(MinecraftHowSignInPolicy.isDnsDashboardUrl("https://minecraft.how/dashboard/dns-other"))
        assertFalse(MinecraftHowSignInPolicy.isDnsDashboardUrl("https://minecraft.how.evil.example/dashboard/dns"))
    }

    @Test fun recognizesOnlyFirstPartyProviderCallbacksForHistoryClearing() {
        assertTrue(MinecraftHowSignInPolicy.isProviderCallbackUrl("https://minecraft.how/auth/google/callback?code=one-time"))
        assertFalse(MinecraftHowSignInPolicy.isProviderCallbackUrl("https://evil.example/auth/google/callback?code=one-time"))
        assertFalse(MinecraftHowSignInPolicy.isProviderCallbackUrl("http://minecraft.how/auth/google/callback"))
        assertFalse(MinecraftHowSignInPolicy.isProviderCallbackUrl("https://minecraft.how/auth/unknown/callback"))
    }

    @Test fun providerClassificationUsesVerifiedHostAndPath() {
        assertEquals(MinecraftHowSignInPolicy.Provider.GOOGLE, MinecraftHowSignInPolicy.providerFor("https://accounts.google.com/v3/signin/identifier"))
        assertEquals(MinecraftHowSignInPolicy.Provider.FACEBOOK, MinecraftHowSignInPolicy.providerFor("https://www.facebook.com/v20.0/dialog/oauth"))
        assertEquals(null, MinecraftHowSignInPolicy.providerFor("https://www.facebook.com/profile.php"))
    }

    @Test fun providerButtonsFollowSiteOrderAndDisabledFlags() {
        assertEquals(
            listOf(MinecraftHowSignInPolicy.Provider.STEAM, MinecraftHowSignInPolicy.Provider.DISCORD),
            MinecraftHowSignInPolicy.enabledProviders(
                listOf("steam", "unknown", "discord", "steam"),
                mapOf("steam" to true, "discord" to true, "facebook" to false),
            ),
        )
        assertEquals(MinecraftHowSignInPolicy.defaultProviders(), MinecraftHowSignInPolicy.enabledProviders(null, null))
    }

    @Test fun providerLaunchUsesTheFirstPartyRouteAndFixedDashboardReturn() {
        assertEquals(
            "https://minecraft.how/auth/discord?redirect=%2Fdashboard%2Fdns",
            MinecraftHowSignInPolicy.providerAuthUrl(MinecraftHowSignInPolicy.Provider.DISCORD),
        )
        assertEquals(
            "https://minecraft.how/auth/google?redirect=%2Fdashboard%2Fdns",
            MinecraftHowSignInPolicy.providerAuthUrl(MinecraftHowSignInPolicy.Provider.GOOGLE),
        )
        assertEquals("https://minecraft.how/terms-of-service", MinecraftHowSignInPolicy.publicPolicyUrl("/terms-of-service"))
        assertEquals("https://minecraft.how/privacy-policy", MinecraftHowSignInPolicy.publicPolicyUrl("/privacy-policy"))
        assertEquals(null, MinecraftHowSignInPolicy.publicPolicyUrl("/auth/google"))
    }

    @Test fun consentAndEmbeddedProviderPolicyGateLaunch() {
        assertTrue(MinecraftHowSignInPolicy.canStartProvider(true, true))
        assertFalse(MinecraftHowSignInPolicy.canStartProvider(false, true))
        assertFalse(MinecraftHowSignInPolicy.canStartProvider(true, false))
    }

    @Test fun googleIsOfferedInAppOnlyWhenABrowserContractCanFinishTheSignIn() {
        val options = listOf(MinecraftHowSignInPolicy.Provider.GOOGLE, MinecraftHowSignInPolicy.Provider.DISCORD, MinecraftHowSignInPolicy.Provider.STEAM)
        assertEquals(
            listOf(MinecraftHowSignInPolicy.Provider.DISCORD, MinecraftHowSignInPolicy.Provider.STEAM),
            MinecraftHowSignInPolicy.inAppProviders(options, browserContractAvailable = false),
        )
        assertEquals(options, MinecraftHowSignInPolicy.inAppProviders(options, browserContractAvailable = true))
    }

    @Test fun blockedGoogleNavigationSaysWhyInsteadOfAGenericBlock() {
        val google = MinecraftHowSignInPolicy.blockedNavigationMessage("https://accounts.google.com/o/oauth2/v2/auth?client_id=x")
        assertTrue(google.startsWith("Google doesn't allow sign-in inside Phoenix."))
        assertTrue(google.contains("browser"))
        assertEquals(
            "Phoenix blocked navigation outside the verified HTTPS sign-in routes.",
            MinecraftHowSignInPolicy.blockedNavigationMessage("https://discord.com/channels/@me"),
        )
    }
}
