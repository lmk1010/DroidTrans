package com.mk.androidtransfer;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.mk.androidtransfer.network.Pairing;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 输配对码。
 *
 * <p>扫码那条路会自动带上配对码，走到这一屏说明用户是手输地址或点雷达进来的。
 * 六位数字做成分格输入 —— 一个普通输入框在这里会让人不确定该填几位。
 *
 * <p>和 iOS 端 PairView 一一对应。真正接收输入的是一个看不见的输入框，
 * 六个格子只负责显示。
 */
public class PairActivity extends AppCompatActivity {

    public static final String EXTRA_CODE = "code";
    private static final int LENGTH = 6;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private String baseUrl;
    private String name;
    private EditText sink;
    private final TextView[] boxes = new TextView[LENGTH];
    private boolean pairing;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        setContentView(R.layout.activity_pair);
        applyInsets();

        baseUrl = getIntent().getStringExtra(HomeActivity.EXTRA_BASE_URL);
        name = getIntent().getStringExtra(HomeActivity.EXTRA_NAME);
        ((TextView) findViewById(R.id.pairName)).setText(name == null ? baseUrl : name);

        LinearLayout row = findViewById(R.id.codeBoxes);
        for (int i = 0; i < LENGTH; i++) {
            boxes[i] = (TextView) row.getChildAt(i);
        }

        sink = findViewById(R.id.codeSink);
        sink.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                render(e.toString());
            }
        });

        findViewById(R.id.codeBoxes).setOnClickListener(v -> focusSink());
        findViewById(R.id.btnCancel).setOnClickListener(v -> finish());

        String scanned = getIntent().getStringExtra(EXTRA_CODE);
        if (scanned != null && scanned.length() == LENGTH) {
            // 扫码那条路二维码里就带着码，用户不该再手输一遍
            sink.setText(scanned);
        } else {
            focusSink();
        }
    }

    private void render(String raw) {
        StringBuilder digits = new StringBuilder();
        for (char c : raw.toCharArray()) {
            if (Character.isDigit(c) && digits.length() < LENGTH) {
                digits.append(c);
            }
        }
        String code = digits.toString();
        if (!code.equals(raw)) {
            sink.setText(code);
            sink.setSelection(code.length());
            return;
        }
        for (int i = 0; i < LENGTH; i++) {
            boxes[i].setText(i < code.length() ? String.valueOf(code.charAt(i)) : "");
            boxes[i].setBackgroundResource(i == code.length()
                    ? R.drawable.code_box_active : R.drawable.code_box);
        }
        if (code.length() == LENGTH && !pairing) {
            submit(code);
        }
    }

    private void submit(String code) {
        pairing = true;
        io.execute(() -> {
            boolean ok = Pairing.pair(this, baseUrl, code);
            main.post(() -> {
                pairing = false;
                if (ok) {
                    Intent i = new Intent(this, HomeActivity.class);
                    i.putExtra(HomeActivity.EXTRA_BASE_URL, baseUrl);
                    i.putExtra(HomeActivity.EXTRA_NAME, name);
                    startActivity(i);
                    finish();
                } else {
                    // 码不对就清空重来，让用户直接接着输，不用先手动删六下
                    Toast.makeText(this, R.string.pair_wrong, Toast.LENGTH_SHORT).show();
                    sink.setText("");
                    focusSink();
                }
            });
        });
    }

    private void focusSink() {
        sink.requestFocus();
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) {
            imm.showSoftInput(sink, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void applyInsets() {
        View content = findViewById(R.id.content);
        ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
            androidx.core.graphics.Insets bars =
                    insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(0, bars.top, 0, bars.bottom);
            return insets;
        });
    }
}
