package com.banking.payment.config;

import feign.Client;
import feign.hc5.ApacheHttp5Client;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.io.HttpClientConnectionManager;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.pool.PoolConcurrencyPolicy;
import org.apache.hc.core5.pool.PoolReusePolicy;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;

import javax.net.ssl.SSLContext;
import java.io.InputStream;
import java.security.KeyStore;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * Apache HttpClient 5 — mTLS + Connection Pool Configuration
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * This class wires together the two layers of the HTTP client stack:
 *
 *   ┌─────────────────────────────────────────────────────────┐
 *   │  Layer 1: OpenFeign                                     │
 *   │  (declarative interface, builds HTTP request)           │
 *   └────────────────────┬────────────────────────────────────┘
 *                        │ delegates to
 *   ┌────────────────────▼────────────────────────────────────┐
 *   │  Layer 2: Apache HttpClient 5  (this class configures)  │
 *   │                                                         │
 *   │  ┌──────────────────────────────────────────────────┐   │
 *   │  │  SSLContext (Apache SSLContextBuilder)            │   │
 *   │  │  ├── KeyStore: client.p12 (our cert + key)       │   │
 *   │  │  └── TrustStore: truststore.jks (trusted CAs)   │   │
 *   │  └───────────────────┬──────────────────────────────┘   │
 *   │                      │                                   │
 *   │  ┌───────────────────▼──────────────────────────────┐   │
 *   │  │  SSLConnectionSocketFactory                       │   │
 *   │  │  Wraps SSLContext for use in the connection pool  │   │
 *   │  └───────────────────┬──────────────────────────────┘   │
 *   │                      │                                   │
 *   │  ┌───────────────────▼──────────────────────────────┐   │
 *   │  │  PoolingHttpClientConnectionManager               │   │
 *   │  │  maxConnTotal=200, maxConnPerRoute=50             │   │
 *   │  │  validateAfterInactivity=10s                      │   │
 *   │  └───────────────────┬──────────────────────────────┘   │
 *   │                      │                                   │
 *   │  ┌───────────────────▼──────────────────────────────┐   │
 *   │  │  CloseableHttpClient                              │   │
 *   │  │  connectTimeout=3s, responseTimeout=5s           │   │
 *   │  └──────────────────────────────────────────────────┘   │
 *   └─────────────────────────────────────────────────────────┘
 *
 * ── mTLS Handshake Flow ────────────────────────────────────────────────────
 *
 * 1. payment-service initiates TLS handshake to https://account-service:8443
 * 2. account-service presents its SERVER certificate (server.p12)
 * 3. payment-service verifies it against truststore.jks → OK (same CA signed it)
 * 4. account-service requests CLIENT certificate (client-auth: need in server yml)
 * 5. payment-service presents its CLIENT certificate (client.p12) ← THIS CLASS loads it
 * 6. account-service verifies it against its truststore.jks → OK
 * 7. mTLS handshake complete — both sides authenticated
 *
 * ── Apache vs Netty mTLS API difference ───────────────────────────────────
 *
 * Apache:  SSLContextBuilder → SSLContext → SSLConnectionSocketFactory
 *          → PoolingHttpClientConnectionManager → CloseableHttpClient
 *
 * Netty:   SslContextBuilder → SslContext (Netty type, not JDK type)
 *          → HttpClient.secure() → ReactorClientHttpConnector
 *
 * Same concept, completely different API (see banking-webclient-netty app).
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class ApacheHttpClientConfig {

    private final MtlsProperties mtlsProperties;
    private final ResourceLoader  resourceLoader;

    // ── Step 1: Build SSLContext with client cert + truststore ────────────────

    /**
     * Creates a JDK SSLContext configured for mTLS using Apache's SSLContextBuilder.
     *
     * Apache HC5 uses the JDK SSLContext (javax.net.ssl.SSLContext) —
     * this is different from Netty's own SslContext.
     */
    @Bean
    public SSLContext mtlsSslContext() throws Exception {
        MtlsProperties.Keystore ks = mtlsProperties.getKeystore();
        MtlsProperties.Keystore ts = mtlsProperties.getTruststore();

        // Load our client keystore (client.p12)
        // This holds payment-service's private key + certificate
        KeyStore keyStore = loadKeyStore(ks.getPath(), ks.getPassword(), ks.getType());

        // Load the truststore (truststore.jks)
        // This tells us which CAs to trust when verifying server certs
        KeyStore trustStore = loadKeyStore(ts.getPath(), ts.getPassword(), ts.getType());

        SSLContext sslContext = SSLContextBuilder.create()
                // Our client certificate — presented to account/customer services
                .loadKeyMaterial(keyStore, ks.getPassword().toCharArray())
                // Certs we trust — used to verify server cert from account/customer services
                .loadTrustMaterial(trustStore, null)
                .build();

        log.info("mTLS SSLContext built — keystore: {}, truststore: {}",
                ks.getPath(), ts.getPath());
        return sslContext;
    }

    // ── Step 2: SSLConnectionSocketFactory ───────────────────────────────────

    /**
     * Wraps the SSLContext in an Apache socket factory.
     * This is what the connection pool uses when creating new HTTPS connections.
     */
    @Bean
    public SSLConnectionSocketFactory sslConnectionSocketFactory(SSLContext sslContext) {
        SSLConnectionSocketFactoryBuilder builder = SSLConnectionSocketFactoryBuilder.create()
                .setSslContext(sslContext)
                .setTlsVersions(
                    org.apache.hc.core5.http.ssl.TLS.V_1_3,
                    org.apache.hc.core5.http.ssl.TLS.V_1_2
                );

        // DEV ONLY — disable hostname verification for self-signed localhost certs
        // In production: NEVER set this — remove this block
        if (mtlsProperties.isDisableHostnameVerification()) {
            log.warn("⚠️  Hostname verification DISABLED — dev mode only, never use in production");
            builder.setHostnameVerifier(NoopHostnameVerifier.INSTANCE);
        }

        return builder.build();
    }

    // ── Step 3: Connection Pool ───────────────────────────────────────────────

    /**
     * Creates a pooled connection manager backed by the mTLS socket factory.
     *
     * Connection pooling is critical for banking:
     * - Without pool: new TCP handshake + TLS handshake on EVERY request (~200ms overhead)
     * - With pool:    connections reused → handshake cost paid once (~5ms per request)
     *
     * validateAfterInactivity: checks idle connections before reuse —
     * prevents "stale connection" errors which are common in banking apps
     * where connections sit idle between payment batches.
     */
    @Bean
    public HttpClientConnectionManager connectionManager(
            SSLConnectionSocketFactory sslSocketFactory) {

        return PoolingHttpClientConnectionManagerBuilder.create()
                .setSSLSocketFactory(sslSocketFactory)
                .setMaxConnTotal(200)              // total connections across all routes
                .setMaxConnPerRoute(50)            // max per downstream service
                .setDefaultSocketConfig(
                    SocketConfig.custom()
                        .setSoTimeout(Timeout.ofSeconds(5))  // socket read timeout
                        .build()
                )
                .setPoolConcurrencyPolicy(PoolConcurrencyPolicy.STRICT)
                .setConnPoolPolicy(PoolReusePolicy.LIFO)    // reuse most-recently-used first
                // Validate connections idle >10s before reuse
                // Prevents stale connection errors (server closed idle connection)
                .setConnectionTimeToLive(TimeValue.ofMinutes(5))
                .build();
    }

    // ── Step 4: CloseableHttpClient ───────────────────────────────────────────

    /**
     * The complete Apache HttpClient with mTLS + pooling + timeouts.
     * This is what Feign uses under the hood for every call it makes.
     */
    @Bean
    public CloseableHttpClient apacheHttpClient(HttpClientConnectionManager connectionManager) {
        return HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(
                    RequestConfig.custom()
                        .setConnectTimeout(Timeout.ofSeconds(3))      // TCP connect timeout
                        .setResponseTimeout(Timeout.ofSeconds(5))     // wait for first byte
                        .setConnectionRequestTimeout(                  // wait for pool slot
                            Timeout.ofMillis(500))
                        .build()
                )
                // Evict expired/idle connections in background
                .evictExpiredConnections()
                .evictIdleConnections(TimeValue.ofMinutes(3))
                .build();
    }

    // ── Step 5: Wire Feign to Use Apache HC5 ──────────────────────────────────

    /**
     * This bean is the critical bridge between Layer 1 (Feign) and Layer 2 (Apache HC5).
     *
     * Without this: Feign uses Java's HttpURLConnection (no pool, no mTLS config)
     * With this:    Feign delegates to our Apache client (pool + mTLS + timeouts)
     *
     * ApacheHttp5Client wraps CloseableHttpClient and implements Feign's Client interface.
     */
    @Bean
    public Client feignApacheHttp5Client(CloseableHttpClient apacheHttpClient) {
        log.info("Feign → Apache HttpClient 5 bridge configured (mTLS enabled)");
        return new ApacheHttp5Client(apacheHttpClient);
    }

    // ── Helper: Load KeyStore from classpath ──────────────────────────────────

    private KeyStore loadKeyStore(String path, String password, String type) throws Exception {
        KeyStore keyStore = KeyStore.getInstance(type);
        try (InputStream is = resourceLoader.getResource(path).getInputStream()) {
            keyStore.load(is, password.toCharArray());
        }
        log.debug("Loaded keystore: {} (type: {})", path, type);
        return keyStore;
    }
}
