/*
 * 	Copyright (c) 2017. Toshi Inc
 *
 * 	This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.toshi.presenter;

import android.app.Activity;
import android.content.Intent;
import android.support.annotation.NonNull;
import android.view.View;
import android.widget.Toast;

import com.jakewharton.rxbinding.widget.RxTextView;
import com.toshi.BuildConfig;
import com.toshi.R;
import com.toshi.crypto.util.TypeConverter;
import com.toshi.model.local.ActivityResultHolder;
import com.toshi.model.local.Network;
import com.toshi.model.local.Networks;
import com.toshi.model.network.Balance;
import com.toshi.util.BuildTypes;
import com.toshi.util.EthUtil;
import com.toshi.util.LogUtil;
import com.toshi.util.PaymentType;
import com.toshi.util.ScannerResultType;
import com.toshi.view.BaseApplication;
import com.toshi.view.activity.ScannerActivity;
import com.toshi.view.activity.SendActivity;
import com.toshi.view.fragment.DialogFragment.PaymentConfirmationDialog;

import java.math.BigDecimal;
import java.math.BigInteger;

import rx.Subscription;
import rx.android.schedulers.AndroidSchedulers;
import rx.schedulers.Schedulers;
import rx.subscriptions.CompositeSubscription;

public class SendPresenter implements Presenter<SendActivity> {

    private static final int PAYMENT_SCAN_REQUEST_CODE = 200;

    private SendActivity activity;
    private CompositeSubscription subscriptions;
    private boolean firstTimeAttaching = true;
    private String encodedEthAmount;

    @Override
    public void onViewAttached(final SendActivity view) {
        this.activity = view;
        if (this.firstTimeAttaching) {
            this.firstTimeAttaching = false;
            initLongLivingObjects();
        }

        initShortLivingObjects();
    }

    private void initLongLivingObjects() {
        this.subscriptions = new CompositeSubscription();
    }

    private void initShortLivingObjects() {
        initNetworkView();
        initUiListeners();
        processIntentData();
    }

    private void initNetworkView() {
        final boolean showNetwork = BuildConfig.BUILD_TYPE.equals(BuildTypes.DEBUG);
        this.activity.getBinding().network.setVisibility(showNetwork ? View.VISIBLE : View.GONE);

        if (showNetwork) {
            final Network network = Networks.getInstance().getCurrentNetwork();
            this.activity.getBinding().network.setText(network.getName());
        }
    }

    private void initUiListeners() {
        this.activity.getBinding().closeButton.setOnClickListener( __ -> this.activity.finish());
        this.activity.getBinding().scan.setOnClickListener(__ -> startScanQrActivity());
        this.activity.getBinding().send.setOnClickListener(__ -> showPaymentConfirmationDialog());

        final Subscription sub =
                RxTextView
                .textChanges(this.activity.getBinding().recipientAddress)
                .subscribe(
                        this::handleRecipientAddressChanged,
                        throwable -> LogUtil.e(getClass(), throwable.toString())
                );

        this.subscriptions.add(sub);
    }

    private void handleRecipientAddressChanged(final CharSequence charSequence) {
        this.activity.getBinding().send.setEnabled(charSequence.length() > 16);
    }

    private void startScanQrActivity() {
        if (this.activity == null) return;
        final Intent intent = new Intent(this.activity, ScannerActivity.class)
                .putExtra(ScannerActivity.SCANNER_RESULT_TYPE, ScannerResultType.PAYMENT_ADDRESS);
        this.activity.startActivityForResult(intent, PAYMENT_SCAN_REQUEST_CODE);
    }

    private void showPaymentConfirmationDialog() {
        final PaymentConfirmationDialog dialog =
                PaymentConfirmationDialog.newInstanceExternalPayment(
                        getRecipientAddress(),
                        this.encodedEthAmount,
                        null,
                        PaymentType.TYPE_SEND
                );
        dialog.setOnPaymentConfirmationApprovedListener(__ -> fetchBalance());
        dialog.show(this.activity.getSupportFragmentManager(), PaymentConfirmationDialog.TAG);
    }

    private void fetchBalance() {
        final Subscription sub =
                BaseApplication
                .get()
                .getBalanceManager()
                .getBalance()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        balance -> checkBalanceAndSendExternalPayment(balance, this.encodedEthAmount),
                        this::handleBalanceError
                );

        this.subscriptions.add(sub);
    }

    private void checkBalanceAndSendExternalPayment(final Balance balance, final String sendAmount) {
        final BigInteger convertedSendAmount = TypeConverter.StringHexToBigInteger(sendAmount);
        final int compareResult = convertedSendAmount.compareTo(balance.getUnconfirmedBalance());
        final boolean validAmount = compareResult == -1 || compareResult == 0;

        if (validAmount) {
            sendExternalPayment(sendAmount);
            return;
        }

        Toast.makeText(this.activity, "Your balance is less than the sending amount", Toast.LENGTH_SHORT).show();
    }

    private void sendExternalPayment(final String encodedEthAmount) {
        BaseApplication
                .get()
                .getTransactionManager()
                .sendExternalPayment(
                        getRecipientAddress(),
                        encodedEthAmount
                );

        this.activity.finish();
    }

    private void handleBalanceError(final Throwable throwable) {
        LogUtil.e(getClass(), "Error when fetching balance " + throwable);
        Toast.makeText(this.activity, "Couldn't not fetch balance", Toast.LENGTH_SHORT).show();
    }

    private void processIntentData() {
        final Intent amountIntent = this.activity.getIntent().getParcelableExtra(SendActivity.EXTRA__INTENT);
        this.encodedEthAmount = amountIntent.getStringExtra(AmountPresenter.INTENT_EXTRA__ETH_AMOUNT);
        generateAmount(this.encodedEthAmount);
    }

    private void generateAmount(final String amountAsEncodedEth) {
        final BigInteger weiAmount = TypeConverter.StringHexToBigInteger(amountAsEncodedEth);
        final BigDecimal ethAmount = EthUtil.weiToEth(weiAmount);
        final String ethAmountString = EthUtil.weiAmountToUserVisibleString(weiAmount);

        final Subscription sub = BaseApplication
                .get()
                .getBalanceManager()
                .convertEthToLocalCurrencyString(ethAmount)
                .subscribe(
                        localAmount -> renderAmount(localAmount, ethAmountString),
                        throwable -> LogUtil.exception(getClass(), throwable)
                );

        this.subscriptions.add(sub);
    }

    private void renderAmount(final String localAmount, final String ethAmount) {
        if (this.activity == null) return;
        final String usdEth = this.activity.getString(R.string.local_dot_eth_amount, localAmount, ethAmount);
        this.activity.getBinding().amount.setText(usdEth);
    }

    @NonNull
    private String getRecipientAddress() {
        final String userInput = this.activity.getBinding().recipientAddress.getText().toString();
        return userInput.contains(":") ? userInput.split(":")[1] : userInput;
    }

    @Override
    public void onViewDetached() {
        this.subscriptions.clear();
        this.activity = null;
    }

    @Override
    public void onDestroyed() {
        this.subscriptions = null;
        this.activity = null;
    }

    public boolean handleActivityResult(final ActivityResultHolder resultHolder) {
        if (resultHolder.getResultCode() != Activity.RESULT_OK || this.activity == null) {
            return false;
        }

        if (resultHolder.getRequestCode() == PAYMENT_SCAN_REQUEST_CODE) {
            addPaymentAddressToUi(resultHolder);
        }

        return true;
    }

    private void addPaymentAddressToUi(final ActivityResultHolder resultHolder) {
        final String paymentAddress = resultHolder.getIntent().getStringExtra(SendActivity.ACTIVITY_RESULT);
        this.activity.getBinding().recipientAddress.setText(paymentAddress);
    }
}
