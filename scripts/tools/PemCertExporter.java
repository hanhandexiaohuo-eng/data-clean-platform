import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Base64;

/**
 * 将 keytool 生成的 PKCS12 转成 Nginx 需要的 PEM（仅测试环境使用）。
 *
 * 用法：java PemCertExporter.java <keystore.p12> <password> <outDir>
 */
public class PemCertExporter {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: PemCertExporter <keystore.p12> <password> <outDir>");
            System.exit(2);
        }
        char[] password = args[1].toCharArray();
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(Path.of(args[0]))) {
            store.load(in, password);
        }
        String alias = store.aliases().nextElement();
        X509Certificate cert = (X509Certificate) store.getCertificate(alias);
        PrivateKey key = (PrivateKey) store.getKey(alias, password);

        Path outDir = Path.of(args[2]);
        Files.createDirectories(outDir);
        writePem(outDir.resolve("fullchain.pem"), "CERTIFICATE", cert.getEncoded());
        writePem(outDir.resolve("privkey.pem"), "PRIVATE KEY", key.getEncoded());
        System.out.println("PEM written to " + outDir);
    }

    private static void writePem(Path path, String label, byte[] der) throws Exception {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(der);
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.US_ASCII)) {
            writer.write("-----BEGIN " + label + "-----\n");
            writer.write(body);
            writer.write("\n-----END " + label + "-----\n");
        }
        // 私钥文件权限收紧（Windows 下尽力而为）
        path.toFile().setReadable(false, false);
        path.toFile().setReadable(true, true);
    }
}
