# Rules applied to any app that embeds this AAR.
#
# The SDK's entry points are referenced from a host app's code and from the
# Android manifest, and R8 cannot see the manifest references -- without these,
# a release build strips the service and the boot receiver and sharing silently
# never starts, which is exactly the kind of bug that only appears in production.
-keep class com.proxypool.sdk.ProxyPoolSdk { *; }
-keep class com.proxypool.sdk.SdkConfig { *; }
-keep class com.proxypool.sdk.SdkState { *; }
-keep class com.proxypool.sdk.SdkState$Status { *; }
-keep class com.proxypool.sdk.TunnelService
-keep class com.proxypool.sdk.BootReceiver
