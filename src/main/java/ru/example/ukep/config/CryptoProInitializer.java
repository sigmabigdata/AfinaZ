package ru.example.ukep.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.Provider;
import java.security.Security;

/**
 * Инициализация CryptoPro JCSP + RevCheck + CAdES.
 *
 * Отключение нативных проверок подписи — критично: иначе JCSP при
 * верификации CAdES ищет корневые сертификаты в /var/opt/cprocsp,
 * которого в контейнере нет, и подпись «невалидна» для всех клиентов.
 */
@Component
public class CryptoProInitializer {

    private static final Logger log = LoggerFactory.getLogger(CryptoProInitializer.class);

    /** Флаг для отключения CryptoPro в средах, где он не установлен. */
    @Value("${crypto.pro.enabled:true}")
    private boolean enabled;

    @PostConstruct
    public void init() {
        System.setProperty("file.encoding", "UTF-8");

        if (!enabled) {
            log.warn("CryptoPro отключён через crypto.pro.enabled=false");
            log.warn("Проверка подписи работать не будет. Это допустимо в dev/test.");
            return;
        }

        installRevocationSystemProperties();
        registerJcspProvider();
        registerRevCheckProvider();
        disableNativeVerify();
    }

    /**
     * Без этих системных свойств CAdES падает с
     * "Could not determine revocation status".
     */
    private void installRevocationSystemProperties() {
        System.setProperty("com.sun.security.enableCRLDP", "true");
        System.setProperty("com.ibm.security.enableCRLDP", "true");
        System.setProperty("ocsp.enable", "true");
        System.setProperty("com.sun.security.enableAIAcaIssuers", "true");
        System.setProperty("ru.CryptoPro.reprov.enableAIAcaIssuers", "true");
        System.setProperty("ru.CryptoPro.reprov.enableCRLDP", "true");
        log.info("Установлены системные свойства для проверки CRL/OCSP");
    }

    /** Регистрирует JCSP (ГОСТ-провайдер). Возвращает имя провайдера или null. */
    private String registerJcspProvider() {
        try {
            Class<?> jcspCls = Class.forName("ru.CryptoPro.JCSP.JCSP");
            Object provider = jcspCls.getDeclaredConstructor().newInstance();
            String name = (String) jcspCls.getField("PROVIDER_NAME").get(null);
            if (Security.getProvider(name) == null) {
                Security.addProvider((Provider) provider);
                log.info("CryptoPro JCSP provider registered: {}", name);
            }
            return name;
        } catch (Exception e) {
            log.warn("JCSP provider не зарегистрирован: {}", e.getMessage());
            return null;
        }
    }

    private void registerRevCheckProvider() {
        try {
            Class<?> revCls = Class.forName("ru.CryptoPro.reprov.RevCheck");
            if (Security.getProvider("RevCheck") == null) {
                Security.addProvider(
                        (Provider) revCls.getDeclaredConstructor().newInstance());
                log.info("CryptoPro RevCheck provider registered");
            }
        } catch (Exception t) {
            log.warn("RevCheck не зарегистрирован: {}", t.getMessage());
        }
    }

    /**
     * Настраивает CAdES так, чтобы использовался Java CertPathBuilder
     * с нашим набором trusted-сертификатов, а не нативная проверка JCSP.
     */
    private void disableNativeVerify() {
        try {
            Class<?> cadesConfig = Class.forName("ru.CryptoPro.CAdES.CAdESConfig");
            Object jcspProvider = Class.forName("ru.CryptoPro.JCSP.JCSP")
                    .getField("PROVIDER_NAME").get(null);
            cadesConfig.getMethod("setDefaultProvider", String.class)
                    .invoke(null, jcspProvider);
            log.info("CAdES default provider set to {}", jcspProvider);

            setProperties();
            boolean done = tryConfigMethods();

            if (!done) {
                log.warn("Reflection setUseNativeVerify: ни один метод не найден, "
                        + "полагаемся на системные свойства");
            }
            log.info("CAdES native verify disabled (attempted)");
        } catch (Exception t) {
            log.warn("CAdESConfig не настроен: {}", t.getMessage());
        }
    }

    private void setProperties() {
        String[] props = {
            "ru.CryptoPro.CAdES.useNativeVerify",
            "ru.CryptoPro.CAdES.nativeVerify",
            "ru.CryptoPro.CAdES.useNative",
            "ru.CryptoPro.AdES.useNativeImpl",
            "ru.CryptoPro.AdES.nativeVerify",
            "ru.CryptoPro.AdES.useNative",
        };
        for (String p : props) System.setProperty(p, "false");
        System.setProperty("ru.CryptoPro.AdES.useBuiltinImpl", "true");
        System.setProperty("ru.CryptoPro.CAdES.disableNativeVerify", "true");
    }

    /** Пытается вызвать setUseNativeVerify(false) через reflection в разных классах. */
    private boolean tryConfigMethods() {
        String[] cfgClasses = {
            "ru.CryptoPro.CAdES.CAdESConfig",
            "ru.CryptoPro.AdES.AdESConfig",
            "ru.CryptoPro.AdES.config.AdESConfig",
        };
        String[] methods = {
            "setUseNativeVerify", "setNativeVerify",
            "setUseBuiltinVerify", "setBuiltinVerify",
            "setUseNativeImpl", "setNativeImpl",
        };
        boolean done = false;
        for (String c : cfgClasses) {
            done = invokeAllMethods(c, methods, done);
        }
        return done;
    }

    /** Пробует все методы у класса c; возвращает true, если хоть один сработал. */
    private boolean invokeAllMethods(String className, String[] methods, boolean done) {
        Class<?> cls;
        try {
            cls = Class.forName(className);
        } catch (Exception e) {
            return done;
        }
        for (String m : methods) {
            try {
                cls.getMethod(m, boolean.class).invoke(null, false);
                log.info("{}#{}(false) — OK", className, m);
                done = true;
            } catch (Exception ignored) {
                // метода нет — идём дальше
            }
        }
        return done;
    }
}
