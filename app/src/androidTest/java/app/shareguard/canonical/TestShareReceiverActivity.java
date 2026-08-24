package app.shareguard.canonical;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.ResultReceiver;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Arrays;

/** Runs in the test APK package without relying on the target app's runtime libraries. */
public final class TestShareReceiverActivity extends Activity {
    public static final String EXTRA_SHA_256 = "test.sha256";
    public static final String EXTRA_BYTE_COUNT = "test.byte_count";
    public static final String EXTRA_WRITE_OPEN_SUCCEEDED = "test.write_open_succeeded";
    public static final String EXTRA_FAILURE_CODE = "test.failure_code";
    public static final String EXTRA_RESULT_RECEIVER = "test.result_receiver";
    public static final String EXTRA_PROBE_URI = "test.probe_uri";

    @Override
    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Uri uri = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            ? getIntent().getParcelableExtra(Intent.EXTRA_STREAM, Uri.class)
            : getIntent().getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri == null) {
            uri = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? getIntent().getParcelableExtra(EXTRA_PROBE_URI, Uri.class)
                : getIntent().getParcelableExtra(EXTRA_PROBE_URI);
        }
        if (uri == null) {
            finishWithFailure("URI_MISSING");
            return;
        }

        byte[] bytes;
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IllegalStateException("URI_UNREADABLE");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            Arrays.fill(buffer, (byte) 0);
            bytes = output.toByteArray();
        } catch (Exception ignored) {
            finishWithFailure("READ_DENIED");
            return;
        }

        try {
            boolean writable = false;
            try (ParcelFileDescriptor descriptor = getContentResolver().openFileDescriptor(uri, "rw")) {
                writable = descriptor != null;
            } catch (Exception ignored) {
                // A read-only grant is the expected path.
            }
            finishWithResult(
                RESULT_OK,
                new Intent()
                    .putExtra(EXTRA_SHA_256, sha256(bytes))
                    .putExtra(EXTRA_BYTE_COUNT, bytes.length)
                    .putExtra(EXTRA_WRITE_OPEN_SUCCEEDED, writable)
            );
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private void finishWithFailure(String code) {
        finishWithResult(RESULT_CANCELED, new Intent().putExtra(EXTRA_FAILURE_CODE, code));
    }

    @SuppressWarnings("deprecation")
    private void finishWithResult(int resultCode, Intent data) {
        setResult(resultCode, data);
        ResultReceiver receiver = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            ? getIntent().getParcelableExtra(EXTRA_RESULT_RECEIVER, ResultReceiver.class)
            : getIntent().getParcelableExtra(EXTRA_RESULT_RECEIVER);
        if (receiver != null) receiver.send(resultCode, data.getExtras());
        finish();
    }

    private static String sha256(byte[] bytes) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (Exception exception) {
            throw new IllegalStateException("SHA256_UNAVAILABLE", exception);
        }
        try {
            StringBuilder value = new StringBuilder(digest.length * 2);
            for (byte item : digest) value.append(String.format("%02x", item & 0xff));
            return value.toString();
        } finally {
            Arrays.fill(digest, (byte) 0);
        }
    }
}
