package com.banking.payment.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds mTLS configuration from application.yml.
 *
 * mTLS requires TWO stores:
 *
 * keystore   — holds OUR private key + certificate.
 *              payment-service presents this to account/customer services
 *              to prove "I am payment-service and I'm authorised".
 *
 * truststore — holds certificates we TRUST.
 *              When account-service presents its server cert,
 *              payment-service checks it against this store.
 *              If the CA that signed the server cert is in here → trusted.
 *
 * Both stores use the same password in this demo (changeit).
 * In production: use Azure Key Vault — never store keystores on disk.
 */
@Data
@Component
@ConfigurationProperties(prefix = "mtls")
public class MtlsProperties {

    private Keystore keystore   = new Keystore();
    private Keystore truststore = new Keystore();

    /** Whether to disable hostname verification (dev only — never in production) */
    private boolean disableHostnameVerification = false;

    @Data
    public static class Keystore {
        /** Path to keystore file, e.g. classpath:certs/client.p12 */
        private String path;
        /** Keystore password */
        private String password;
        /** Keystore type: PKCS12 or JKS */
        private String type = "PKCS12";
    }
}
