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

package com.toshi.manager;

import android.support.annotation.NonNull;
import android.util.Pair;

import com.toshi.R;
import com.toshi.crypto.HDWallet;
import com.toshi.exception.UnknownTransactionException;
import com.toshi.manager.model.PaymentTask;
import com.toshi.manager.network.EthereumService;
import com.toshi.manager.store.PendingTransactionStore;
import com.toshi.model.local.PendingTransaction;
import com.toshi.model.local.Recipient;
import com.toshi.model.local.SendState;
import com.toshi.model.local.UnsignedW3Transaction;
import com.toshi.model.local.User;
import com.toshi.model.network.SentTransaction;
import com.toshi.model.network.ServerTime;
import com.toshi.model.network.SignedTransaction;
import com.toshi.model.network.TransactionRequest;
import com.toshi.model.network.UnsignedTransaction;
import com.toshi.model.sofa.Payment;
import com.toshi.model.sofa.PaymentRequest;
import com.toshi.model.sofa.SofaAdapters;
import com.toshi.model.sofa.SofaMessage;
import com.toshi.model.sofa.SofaType;
import com.toshi.util.LocaleUtil;
import com.toshi.util.LogUtil;
import com.toshi.view.BaseApplication;
import com.toshi.view.notification.ChatNotificationManager;
import com.toshi.view.notification.ExternalPaymentNotificationManager;

import java.io.IOException;

import rx.Observable;
import rx.Single;
import rx.Subscription;
import rx.schedulers.Schedulers;
import rx.subjects.PublishSubject;
import rx.subscriptions.CompositeSubscription;

import static com.toshi.manager.model.PaymentTask.INCOMING;
import static com.toshi.manager.model.PaymentTask.OUTGOING;
import static com.toshi.manager.model.PaymentTask.OUTGOING_EXTERNAL;

public class TransactionManager {

    private final PublishSubject<PaymentTask> newOutgoingPaymentQueue = PublishSubject.create();
    private final PublishSubject<PaymentTask> newIncomingPaymentQueue = PublishSubject.create();
    private final PublishSubject<Payment> updatePaymentQueue = PublishSubject.create();

    private HDWallet wallet;
    private PendingTransactionStore pendingTransactionStore;
    private CompositeSubscription subscriptions;

    /*package */ TransactionManager() {
        initDatabase();
        initSubscriptions();
    }

    private void initDatabase() {
        this.pendingTransactionStore = new PendingTransactionStore();
    }

    private void initSubscriptions() {
        this.subscriptions = new CompositeSubscription();
    }

    public TransactionManager init(final HDWallet wallet) {
        this.wallet = wallet;
        new Thread(this::initEverything).start();
        return this;
    }

    private void initEverything() {
        updatePendingTransactions();
        attachSubscribers();
    }

    private void attachSubscribers() {
        attachNewOutgoingPaymentSubscriber();
        attachNewIncomingPaymentSubscriber();
        attachUpdatePaymentSubscriber();
    }

    private void attachNewOutgoingPaymentSubscriber() {
        final Subscription sub =
                this.newOutgoingPaymentQueue
                .observeOn(Schedulers.io())
                .subscribeOn(Schedulers.io())
                .subscribe(
                        this::processNewOutgoingPayment,
                        this::handleNonFatalError
                );

        this.subscriptions.add(sub);
    }

    private void processNewOutgoingPayment(final PaymentTask paymentTask) {
        final Single<Pair<Payment, SofaMessage>> storePaymentSingle = getUpdatedPayment(paymentTask);
        switch (paymentTask.getAction()) {
            case OUTGOING:
                storePaymentSingle.subscribe(
                        pair -> handleOutgoingPayment(paymentTask.getUser(), pair.first, pair.second),
                        this::handleNonFatalError
                );
                break;
            case OUTGOING_EXTERNAL:
                storePaymentSingle.subscribe(
                        pair -> handleOutgoingExternalPayment(pair.first, pair.second),
                        this::handleNonFatalError
                );
                break;
        }
    }

    private void attachNewIncomingPaymentSubscriber() {
        final Subscription sub =
                this.newIncomingPaymentQueue
                .observeOn(Schedulers.io())
                .subscribeOn(Schedulers.io())
                .subscribe(
                        this::processNewIncomingPayment,
                        this::handleNonFatalError
                );

        this.subscriptions.add(sub);
    }

    private void processNewIncomingPayment(final PaymentTask paymentTask) {
        getUpdatedPayment(paymentTask)
                .subscribe(
                        pair -> handleIncomingPayment(pair.first, pair.second),
                        this::handleNonFatalError
                );
    }

    private Single<Pair<Payment, SofaMessage>> getUpdatedPayment(final PaymentTask paymentTask) {
        final Payment payment = paymentTask.getPayment();
        final User sender = getSenderFromTask(paymentTask);
        final User receiver = getReceiverFromTask(paymentTask);
        return payment
                .generateLocalPrice()
                .flatMap(updatedPayment ->
                        Single.zip(
                                Single.just(updatedPayment),
                                Single.just(storePayment(receiver, updatedPayment, sender)),
                                Pair::new
                        )
                )
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.io());
    }

    private void attachUpdatePaymentSubscriber() {
        final Subscription sub =
                this.updatePaymentQueue
                .observeOn(Schedulers.io())
                .subscribeOn(Schedulers.io())
                .filter(payment -> payment != null)
                .subscribe(
                        this::processUpdatedPayment,
                        this::handleUpdatePaymentError
                );

        this.subscriptions.add(sub);
    }

    private void processUpdatedPayment(final Payment payment) {
        this.pendingTransactionStore
                .loadTransaction(payment.getTxHash())
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.io())
                .subscribe(
                        pendingTransaction -> updatePendingTransaction(pendingTransaction, payment),
                        this::handleNonFatalError
                );
    }

    public void sendPayment(final User receiver, final String paymentAddress, final String amount) {
        new Payment()
            .setValue(amount)
            .setFromAddress(this.wallet.getPaymentAddress())
            .setToAddress(paymentAddress)
            .generateLocalPrice()
            .subscribe(
                    payment -> addOutgoingPaymentTask(receiver, payment, OUTGOING),
                    this::handleNonFatalError
            );
    }

    private void addOutgoingPaymentTask(final User receiver, final Payment payment, final @PaymentTask.Action int action) {
        final PaymentTask task = new PaymentTask(receiver, payment, action);
        this.newOutgoingPaymentQueue.onNext(task);
    }

    public final void updatePayment(final Payment payment) {
        this.updatePaymentQueue.onNext(payment);
    }

    public final void updatePaymentRequestState(final User remoteUser,
                                                final SofaMessage sofaMessage,
                                                final @PaymentRequest.State int newState) {
        try {
            final PaymentRequest paymentRequest =
                    SofaAdapters.get()
                    .txRequestFrom(sofaMessage.getPayload())
                    .setState(newState);

            final Recipient recipient = new Recipient(remoteUser);
            final String updatedPayload = SofaAdapters.get().toJson(paymentRequest);
            sofaMessage.setPayload(updatedPayload);
            BaseApplication
                    .get()
                    .getSofaMessageManager()
                    .updateMessage(recipient, sofaMessage);
        } catch (final IOException ex) {
            LogUtil.exception(getClass(), "Error changing Payment Request state", ex);
        }
    }

    private void updatePendingTransactions() {
        final Subscription sub = this.pendingTransactionStore
                .loadAllTransactions()
                .filter(this::isUnconfirmed)
                .flatMap(pendingTransaction -> Observable.zip(
                        Observable.just(pendingTransaction),
                        getTransactionStatus(pendingTransaction),
                        Pair::new
                ))
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.io())
                .onErrorReturn(__ -> new Pair<>(null, null))
                .subscribe(
                        pair -> this.updatePendingTransaction(pair.first, pair.second),
                        this::handlePendingTransactionError
                );

        this.subscriptions.add(sub);
    }

    @NonNull
    private Observable<Payment> getTransactionStatus(final PendingTransaction pendingTransaction) {
        return BaseApplication
                .get()
                .getBalanceManager()
                .getTransactionStatus(pendingTransaction.getTxHash())
                .toObservable();
    }

    private Boolean isUnconfirmed(final PendingTransaction pendingTransaction) {
        try {
            final SofaMessage sofaMessage = pendingTransaction.getSofaMessage();
            final Payment payment = SofaAdapters.get().paymentFrom(sofaMessage.getPayload());
            return payment.getStatus().equals(SofaType.UNCONFIRMED);
        } catch (final IOException ex) {
            return false;
        }
    }

    public void sendExternalPayment(final String paymentAddress, final String amount) {
        new Payment()
                .setToAddress(paymentAddress)
                .setFromAddress(this.wallet.getPaymentAddress())
                .setValue(amount)
                .generateLocalPrice()
                .subscribe(
                        payment -> addOutgoingPaymentTask(null, payment, OUTGOING_EXTERNAL),
                        this::handleNonFatalError
                );
    }

    private void handleIncomingPayment(final Payment payment, final SofaMessage storedSofaMessage) {
        final PendingTransaction pendingTransaction =
                new PendingTransaction()
                        .setTxHash(payment.getTxHash())
                        .setSofaMessage(storedSofaMessage);
        this.pendingTransactionStore.save(pendingTransaction);
    }

    public void addIncomingPayment(final Payment payment) {
        BaseApplication
                .get()
                .getRecipientManager()
                .getUserFromPaymentAddress(payment.getFromAddress())
                .subscribe(
                        (sender) -> addIncomingPaymentTask(sender, payment),
                        this::handleNonFatalError
                );
    }

    private void addIncomingPaymentTask(final User sender, final Payment payment) {
        final PaymentTask task = new PaymentTask(sender, payment, INCOMING);
        this.newIncomingPaymentQueue.onNext(task);
    }

    private User getSenderFromTask(final PaymentTask task) {
        if (task.getAction() == INCOMING) return task.getUser();
        if (task.getAction() == OUTGOING) return getCurrentLocalUser();
        if (task.getAction() == OUTGOING_EXTERNAL) return getCurrentLocalUser();
        throw new IllegalStateException("Unknown payment task action.");
    }

    private User getReceiverFromTask(final PaymentTask task) {
        if (task.getAction() == INCOMING) return task.getUser();
        if (task.getAction() == OUTGOING) return task.getUser();
        if (task.getAction() == OUTGOING_EXTERNAL) return null;
        throw new IllegalStateException("Unknown payment task action.");
    }

    private void handleNonFatalError(final Throwable throwable) {
        LogUtil.exception(getClass(), "Non-fatal error", throwable);
    }

    private SofaMessage storePayment(final User receiver, final Payment payment, final User sender) {
        final SofaMessage sofaMessage = generateMessageFromPayment(payment, sender);
        storeMessage(receiver, sofaMessage);
        return sofaMessage;
    }

    private void storeMessage(final User receiver, final SofaMessage message) {
        // receiver will be null if this is an external payment
        if (receiver == null) return;

        message.setSendState(SendState.STATE_SENDING);
        BaseApplication
                .get()
                .getSofaMessageManager()
                .saveTransaction(receiver, message);
    }

    private void handleOutgoingPayment(final User receiver, final Payment payment, final SofaMessage storedSofaMessage) {
        createUnsignedTransaction(payment)
                .flatMap(this::signAndSendTransaction)
                .observeOn(Schedulers.io())
                .subscribeOn(Schedulers.io())
                .subscribe(
                        sentTransaction -> handleOutgoingPaymentSuccess(sentTransaction, receiver, payment, storedSofaMessage),
                        error -> handleOutgoingPaymentError(error, receiver, storedSofaMessage)
                );
    }

    private void handleOutgoingPaymentSuccess(
            final SentTransaction sentTransaction,
            final User receiver,
            final Payment payment,
            final SofaMessage storedSofaMessage) {
        final String txHash = sentTransaction.getTxHash();
        payment.setTxHash(txHash);

        // Update the stored message with the transactions details
        final SofaMessage updatedMessage = generateMessageFromPayment(payment, getCurrentLocalUser());
        storedSofaMessage.setPayload(updatedMessage.getPayloadWithHeaders());
        updateMessageState(receiver, storedSofaMessage, SendState.STATE_SENT);
        storeUnconfirmedTransaction(txHash, storedSofaMessage);

        final Recipient recipient = new Recipient(receiver);
        BaseApplication
                .get()
                .getSofaMessageManager()
                .sendMessage(recipient, storedSofaMessage);
    }

    private void handleOutgoingPaymentError(
            final Throwable error,
            final User receiver,
            final SofaMessage storedSofaMessage) {
        LogUtil.exception(getClass(), "Error creating transaction", error);
        updateMessageState(receiver, storedSofaMessage, SendState.STATE_FAILED);
        showOutgoingPaymentFailedNotification(receiver);
    }

    private void showOutgoingPaymentFailedNotification(final User user) {
        final String content = getNotificationContent(user.getDisplayName());
        final Recipient recipient = new Recipient(user);
        ChatNotificationManager.showChatNotification(recipient, content);
    }

    private String getNotificationContent(final String content) {
        return String.format(
                LocaleUtil.getLocale(),
                BaseApplication.get().getString(R.string.payment_failed_message),
                content);
    }

    private void handleOutgoingExternalPayment(final Payment payment, final SofaMessage sofaMessage) {
        createUnsignedTransaction(payment)
                .flatMap(this::signAndSendTransaction)
                .observeOn(Schedulers.io())
                .subscribeOn(Schedulers.io())
                .subscribe(
                        sentTransaction -> handleOutgoingExternalPaymentSuccess(sentTransaction, sofaMessage),
                        error -> handleOutgoingExternalPaymentError(error, payment)
                );
    }

    private void handleOutgoingExternalPaymentSuccess(final SentTransaction sentTransaction, final SofaMessage sofaMessage) {
        final String txHash = sentTransaction.getTxHash();
        storeUnconfirmedTransaction(txHash, sofaMessage);
    }

    private void handleOutgoingExternalPaymentError(final Throwable error, final Payment payment) {
        LogUtil.exception(getClass(), "Error sending external payment.", error);
        final String paymentAddress = payment.getToAddress();
        ExternalPaymentNotificationManager.showExternalPaymentFailed(paymentAddress);
    }

    public Single<SignedTransaction> signW3Transaction(final UnsignedW3Transaction transaction) {
        final TransactionRequest transactionRequest = generateTransactionRequest(transaction);
        return EthereumService
                .getApi()
                .createTransaction(transactionRequest)
                .flatMap(this::signTransaction);
    }

    private Single<UnsignedTransaction> createUnsignedTransaction(final Payment payment) {
        final TransactionRequest transactionRequest = generateTransactionRequest(payment);
        return EthereumService
                .getApi()
                .createTransaction(transactionRequest);
    }

    private TransactionRequest generateTransactionRequest(final Payment payment) {
        return new TransactionRequest()
                .setValue(payment.getValue())
                .setFromAddress(payment.getFromAddress())
                .setToAddress(payment.getToAddress());
    }

    private TransactionRequest generateTransactionRequest(final UnsignedW3Transaction transaction) {
        return new TransactionRequest()
                .setValue(transaction.getValue())
                .setFromAddress(transaction.getFrom())
                .setToAddress(transaction.getTo())
                .setData(transaction.getData())
                .setGas(transaction.getGas())
                .setGasPrice(transaction.getGas());
    }

    private Single<SentTransaction> signAndSendTransaction(final UnsignedTransaction unsignedTransaction) {
        return Single.zip(
                    signTransaction(unsignedTransaction),
                    getServerTime(),
                    Pair::new)
                .flatMap(pair -> sendSignedTransaction(pair.first, pair.second));
    }

    private Single<ServerTime> getServerTime() {
        return EthereumService
                .getApi()
                .getTimestamp();
    }

    private Single<SignedTransaction> signTransaction(final UnsignedTransaction unsignedTransaction) {
        final String signature = this.wallet.signTransaction(unsignedTransaction.getTransaction());
        final SignedTransaction signedTransaction =
                new SignedTransaction()
                .setEncodedTransaction(unsignedTransaction.getTransaction())
                .setSignature(signature);

        return Single.just(signedTransaction);
    }

    private Single<SentTransaction> sendSignedTransaction(final SignedTransaction signedTransaction, final ServerTime serverTime) {
        final long timestamp = serverTime.get();
        return EthereumService
                .getApi()
                .sendSignedTransaction(timestamp, signedTransaction);
    }

    private void updateMessageState(final User user, final SofaMessage message, final @SendState.State int sendState) {
        message.setSendState(sendState);
        updateMessage(user, message);
    }

    private void updateMessage(final User user, final SofaMessage message) {
        final Recipient recipient = new Recipient(user);
        BaseApplication
                .get()
                .getSofaMessageManager()
                .updateMessage(recipient, message);
    }


    private SofaMessage generateMessageFromPayment(final Payment payment, final User sender) {
        final String messageBody = SofaAdapters.get().toJson(payment);
        return new SofaMessage().makeNewFromTransaction(payment.getTxHash(), sender, messageBody);
    }

    private void handleUpdatePaymentError(final Throwable throwable) {
        LogUtil.exception(getClass(), "Error when updating payment", throwable);
    }

    private void storeUnconfirmedTransaction(final String txHash, final SofaMessage message) {
        final PendingTransaction pendingTransaction = new PendingTransaction()
                                                            .setSofaMessage(message)
                                                            .setTxHash(txHash);
        this.pendingTransactionStore.save(pendingTransaction);
    }

    // Returns false if this is a new transaction that the app is unaware of.
    // Returns true if the transaction was correctly updated.
    private boolean updatePendingTransaction(final PendingTransaction pendingTransaction, final Payment updatedPayment) {
        if (pendingTransaction == null) {
            return false;
        }

        final SofaMessage updatedMessage;
        try {
            updatedMessage = updateStatusFromPendingTransaction(pendingTransaction, updatedPayment);
        } catch (final IOException | UnknownTransactionException ex) {
            LogUtil.exception(getClass(), "Unable to update pending transaction", ex);
            return false;
        }

        final PendingTransaction updatedPendingTransaction = new PendingTransaction()
                .setTxHash(pendingTransaction.getTxHash())
                .setSofaMessage(updatedMessage);

        this.pendingTransactionStore.save(updatedPendingTransaction);
        return true;
    }

    private void handlePendingTransactionError(final Throwable throwable) {
        LogUtil.exception(getClass(), "Error during updating pending transaction", throwable);
    }

    private SofaMessage updateStatusFromPendingTransaction(final PendingTransaction pendingTransaction, final Payment updatedPayment) throws IOException, UnknownTransactionException {
        if (pendingTransaction == null) {
            throw new UnknownTransactionException("PendingTransaction could not be found. This transaction probably came from outside of Toshi.");
        }

        final SofaMessage sofaMessage = pendingTransaction.getSofaMessage();
        final Payment existingPayment = SofaAdapters.get().paymentFrom(sofaMessage.getPayload());

        existingPayment.setStatus(updatedPayment.getStatus());

        final String messageBody = SofaAdapters.get().toJson(existingPayment);
        return sofaMessage.setPayload(messageBody);
    }

    public PublishSubject<PendingTransaction> getPendingTransactionObservable() {
        return this.pendingTransactionStore.getPendingTransactionObservable();
    }

    private User getCurrentLocalUser() {
        // Yes, this blocks. But realistically, a value should be always ready for returning.
        return BaseApplication
                .get()
                .getUserManager()
                .getCurrentUser()
                .toBlocking()
                .value();
    }

    public void clear() {
        this.subscriptions.clear();
    }
}