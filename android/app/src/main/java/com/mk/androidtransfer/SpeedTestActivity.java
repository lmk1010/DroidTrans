package com.mk.androidtransfer;

import android.os.Bundle;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.mk.androidtransfer.network.FastTransferClient;
import com.mk.androidtransfer.network.ProtocolSelector;
import com.mk.androidtransfer.network.RetrofitClient;
import com.mk.androidtransfer.network.TransferProtocol;

import java.io.IOException;
import java.io.InputStream;

/**
 * 对电脑端 800MB 测 TCP / FTP / HTTP PUT，不走 USB。
 */
public class SpeedTestActivity extends AppCompatActivity {

    private static final long SIZE = 800L * 1024 * 1024;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_speed_test);

        TextInputEditText etServer = findViewById(R.id.etServer);
        TextView tvResult = findViewById(R.id.tvResult);
        MaterialButton btn = findViewById(R.id.btnStart);

        String extra = getIntent().getStringExtra("server_url");
        if (extra != null && !extra.isEmpty()) {
            etServer.setText(extra);
        }

        btn.setOnClickListener(v -> {
            String url = String.valueOf(etServer.getText()).trim();
            btn.setEnabled(false);
            tvResult.setText(R.string.probing_channels);
            new Thread(() -> runBench(url, tvResult, btn)).start();
        });
    }

    private void runBench(String url, TextView tvResult, MaterialButton btn) {
        StringBuilder log = new StringBuilder();
        try {
            ProtocolSelector.Choice auto = ProtocolSelector.select(url);
            log.append(getString(R.string.auto_select, auto.protocol.getLabel(this)));
            post(tvResult, log.toString());

            TransferProtocol[] protocols = {
                    TransferProtocol.TCP, TransferProtocol.FTP, TransferProtocol.HTTP_PUT
            };
            for (TransferProtocol protocol : protocols) {
                ProtocolSelector.Choice choice = new ProtocolSelector.Choice(
                        protocol, auto.host, auto.httpPort, auto.tcpPort, auto.ftpPort);
                log.append(getString(R.string.start_protocol, protocol.getLabel(this)));
                post(tvResult, log.toString());
                long t0 = System.nanoTime();
                FastTransferClient.sendGenerated(
                        choice,
                        "bench-800mb.bin",
                        SIZE,
                        new RepeatStream(SIZE),
                        "speedtest",
                        RetrofitClient.getInstance(url).getOkHttpClient(),
                        new FastTransferClient.ProgressListener() {
                            @Override
                            public boolean isCancelled() {
                                return false;
                            }

                            @Override
                            public void onBytes(long sent, long total) {
                            }
                        }
                );
                double sec = (System.nanoTime() - t0) / 1e9;
                double mbps = (SIZE / 1024.0 / 1024.0) / sec;
                log.append(String.format("  %s  %.2f s  %.1f MB/s\n\n", protocol.getLabel(this), sec, mbps));
                post(tvResult, log.toString());
            }
        } catch (Exception e) {
            log.append(getString(R.string.failed_prefix, e.getMessage()));
            post(tvResult, log.toString());
        }
        runOnUiThread(() -> btn.setEnabled(true));
    }

    private void post(TextView tv, String text) {
        runOnUiThread(() -> tv.setText(text));
    }

    private static class RepeatStream extends InputStream {
        private long left;
        private final byte[] block = new byte[65536];

        RepeatStream(long size) {
            this.left = size;
            for (int i = 0; i < block.length; i++) {
                block[i] = (byte) i;
            }
        }

        @Override
        public int read() throws IOException {
            if (left <= 0) return -1;
            left--;
            return block[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (left <= 0) return -1;
            int n = (int) Math.min(len, Math.min(block.length, left));
            System.arraycopy(block, 0, b, off, n);
            left -= n;
            return n;
        }
    }
}
