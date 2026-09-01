package com.mk.androidtransfer;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;
import com.mk.androidtransfer.license.License;
import com.mk.androidtransfer.license.LicenseApi;
import com.mk.androidtransfer.license.LicenseException;
import com.mk.androidtransfer.license.LicenseStore;

import java.text.DateFormat;
import java.util.Date;

/** Account, Pro activation, and license recovery. */
public final class MeActivity extends AppCompatActivity {
    public static final String EXTRA_FOCUS_PRO = "focus_pro";

    private LicenseStore store;
    private TextView statusTitle;
    private TextView statusDetail;
    private View activeGroup;
    private View freeGroup;
    private View problemGroup;
    private TextView planValue;
    private TextView codeValue;
    private TextView emailValue;
    private TextView expiryValue;
    private TextView problemText;
    private EditText codeInput;
    private MaterialButton activateButton;
    private MaterialButton problemAction;
    private boolean busy;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        setContentView(R.layout.activity_me);
        applyInsets();

        store = LicenseStore.get(this);
        bindViews();
        bindActions();
        render();
        store.refreshIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (store != null) {
            store.reload();
            render();
        }
    }

    private void bindViews() {
        statusTitle = findViewById(R.id.memberStatusTitle);
        statusDetail = findViewById(R.id.memberStatusDetail);
        activeGroup = findViewById(R.id.memberActiveGroup);
        freeGroup = findViewById(R.id.memberFreeGroup);
        problemGroup = findViewById(R.id.memberProblemGroup);
        planValue = findViewById(R.id.memberPlanValue);
        codeValue = findViewById(R.id.memberCodeValue);
        emailValue = findViewById(R.id.memberEmailValue);
        expiryValue = findViewById(R.id.memberExpiryValue);
        problemText = findViewById(R.id.memberProblemText);
        codeInput = findViewById(R.id.memberCodeInput);
        activateButton = findViewById(R.id.memberActivate);
        problemAction = findViewById(R.id.memberProblemAction);
    }

    private void bindActions() {
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.memberPricing).setOnClickListener(v -> openPricing());
        findViewById(R.id.memberHistory).setOnClickListener(
                v -> startActivity(new Intent(this, UploadHistoryActivity.class)));
        findViewById(R.id.memberRefresh).setOnClickListener(v -> refreshLicense());
        findViewById(R.id.memberDeactivate).setOnClickListener(v -> confirmDeactivate());
        activateButton.setOnClickListener(v -> activate());
    }

    private void render() {
        License license = store.getLicense();
        LicenseException problem = store.getProblem();
        boolean active = store.isPro();

        activeGroup.setVisibility(active ? View.VISIBLE : View.GONE);
        freeGroup.setVisibility(license == null && problem == null ? View.VISIBLE : View.GONE);
        problemGroup.setVisibility(problem != null ? View.VISIBLE : View.GONE);

        if (active && license != null) {
            statusTitle.setText(R.string.member_active);
            statusDetail.setText(R.string.member_active_detail);
            planValue.setText(getString(R.string.member_plan_format, planText(license)));
            codeValue.setText(getString(R.string.member_code_format, license.getCode()));
            emailValue.setText(getString(R.string.member_email_format, license.getEmail()));
            Long expires = license.expiresAtMillis();
            String expiry = expires == null
                    ? getString(R.string.member_never)
                    : DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(expires));
            expiryValue.setText(getString(R.string.member_expiry_format, expiry));
            return;
        }

        if (problem != null) {
            renderProblem(problem);
            return;
        }

        statusTitle.setText(R.string.member_free);
        statusDetail.setText(R.string.member_free_detail);
    }

    private void renderProblem(LicenseException problem) {
        switch (problem.getReason()) {
            case EXPIRED:
                statusTitle.setText(R.string.member_expired);
                statusDetail.setText(R.string.member_expired_detail);
                problemText.setText(R.string.member_expired_recovery);
                problemAction.setText(R.string.member_view_pricing);
                problemAction.setOnClickListener(v -> openPricing());
                break;
            case STALE:
                statusTitle.setText(R.string.member_needs_online);
                statusDetail.setText(R.string.member_stale_detail);
                problemText.setText(R.string.member_stale_recovery);
                problemAction.setText(R.string.member_refresh);
                problemAction.setOnClickListener(v -> refreshLicense());
                break;
            default:
                statusTitle.setText(R.string.member_invalid);
                statusDetail.setText(R.string.member_invalid_detail);
                problemText.setText(R.string.member_invalid_recovery);
                problemAction.setText(R.string.member_remove_local);
                problemAction.setOnClickListener(v -> {
                    store.remove();
                    render();
                });
                break;
        }
    }

    private void activate() {
        if (busy) return;
        String normalized = LicenseApi.normalizeCode(codeInput.getText().toString());
        if (normalized.isEmpty()) {
            codeInput.setError(getString(R.string.member_code_invalid));
            return;
        }
        setBusy(true);
        LicenseApi.activate(normalized, store.getDeviceId(), new LicenseApi.ResultCallback() {
            @Override
            public void onSuccess(String token) {
                runOnUiThread(() -> {
                    try {
                        store.save(token);
                        codeInput.setText("");
                        render();
                    } catch (LicenseException e) {
                        showMessage(R.string.member_response_invalid);
                    } finally {
                        setBusy(false);
                    }
                });
            }

            @Override
            public void onFailure(LicenseApi.ApiException error) {
                runOnUiThread(() -> {
                    setBusy(false);
                    showMessage(apiMessage(error));
                });
            }
        });
    }

    private void refreshLicense() {
        if (busy) return;
        String token = store.getToken();
        if (token == null) return;
        setBusy(true);
        LicenseApi.refresh(token, store.getDeviceId(), new LicenseApi.ResultCallback() {
            @Override
            public void onSuccess(String freshToken) {
                runOnUiThread(() -> {
                    try {
                        store.save(freshToken);
                        render();
                        showMessage(R.string.member_refresh_success);
                    } catch (LicenseException e) {
                        showMessage(R.string.member_response_invalid);
                    } finally {
                        setBusy(false);
                    }
                });
            }

            @Override
            public void onFailure(LicenseApi.ApiException error) {
                runOnUiThread(() -> {
                    setBusy(false);
                    showMessage(apiMessage(error));
                });
            }
        });
    }

    private void confirmDeactivate() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.member_remove_title)
                .setMessage(R.string.member_remove_message)
                .setPositiveButton(R.string.member_remove_local,
                        (dialog, which) -> deactivate())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void deactivate() {
        if (busy) return;
        String token = store.getToken();
        if (token == null || token.isEmpty()) {
            store.remove();
            render();
            return;
        }
        setBusy(true);
        LicenseApi.deactivate(token, store.getDeviceId(), new LicenseApi.ActionCallback() {
            @Override
            public void onSuccess() {
                runOnUiThread(() -> {
                    store.remove();
                    render();
                    setBusy(false);
                    showMessage(R.string.member_deactivate_success);
                });
            }

            @Override
            public void onFailure(LicenseApi.ApiException error) {
                runOnUiThread(() -> {
                    setBusy(false);
                    showMessage(apiMessage(error));
                });
            }
        });
    }

    private void openPricing() {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(LicenseApi.PRICING_URL)));
    }

    private void setBusy(boolean value) {
        busy = value;
        activateButton.setEnabled(!value);
        problemAction.setEnabled(!value);
        findViewById(R.id.memberRefresh).setEnabled(!value);
    }

    private int apiMessage(LicenseApi.ApiException error) {
        switch (error.getCode()) {
            case "not_found":
                return R.string.member_err_not_found;
            case "revoked":
                return R.string.member_err_revoked;
            case "too_many_activations":
                return R.string.member_err_too_many;
            case "invalid_code":
                return R.string.member_code_invalid;
            case "invalid_license":
            case "device_mismatch":
            case "device_deactivated":
            case "missing_device_id":
                return R.string.member_err_device;
            case "no_license":
            case "bad_response":
                return R.string.member_response_invalid;
            default:
                return R.string.member_err_network;
        }
    }

    private String planText(License license) {
        switch (license.getPlan()) {
            case "lifetime":
                return getString(R.string.member_plan_lifetime);
            case "years3":
                return getString(R.string.member_plan_years3);
            default:
                return getString(R.string.member_plan_year);
        }
    }

    private void showMessage(int message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void applyInsets() {
        View content = findViewById(R.id.content);
        ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
            androidx.core.graphics.Insets bars =
                    insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), bars.top,
                    v.getPaddingRight(), bars.bottom);
            return insets;
        });
    }
}
