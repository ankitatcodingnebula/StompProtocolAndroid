package ua.naiksoftware.stomp;

import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import rx.Completable;
import rx.Observable;
import rx.Subscriber;
import rx.subjects.PublishSubject;

/* package */ class OkHttpConnectionProvider implements ConnectionProvider {

    private static final String TAG = WebSocketsConnectionProvider.class.getSimpleName();

    private final String mUri;
    private final Map<String, String> mConnectHttpHeaders;
    private final OkHttpClient mOkHttpClient;

    private final List<Subscriber<? super LifecycleEvent>> mLifecycleSubscribers;
    private final PublishSubject<LifecycleEvent> mLifecycleStream;
    private final List<Subscriber<? super String>> mMessagesSubscribers;
    private final PublishSubject<String> mMessagesStream;

    private WebSocket openedSocked;


    /* package */ OkHttpConnectionProvider(String uri, Map<String, String> connectHttpHeaders, OkHttpClient okHttpClient) {
        mUri = uri;
        mConnectHttpHeaders = connectHttpHeaders != null ? connectHttpHeaders : new HashMap<>();
        mLifecycleSubscribers = new ArrayList<>();
        mMessagesSubscribers = new ArrayList<>();
        mOkHttpClient = okHttpClient;

        mLifecycleStream = PublishSubject.create();
        mMessagesStream = PublishSubject.create();
    }

    @Override
    public Observable<String> messages() {
        createWebSocketConnection();
        // By using Subjects, we can leave the tracking of Subscribers to Rx.
        // Additionally, server disconnection is now handled manually
        //    (instead of trying to support disconnecting just by unsubscribing)
        return mMessagesStream;

        /*
        Observable<String> observable = Observable.<String>create(subscriber -> {
            mMessagesSubscribers.add(subscriber);

        }).doOnUnsubscribe(() -> {
            Iterator<Subscriber<? super String>> iterator = mMessagesSubscribers.iterator();
            while (iterator.hasNext()) {
                if (iterator.next().isUnsubscribed()) iterator.remove();
            }

            if (mMessagesSubscribers.size() < 1) {
                Log.d(TAG, "Close web socket connection now in thread " + Thread.currentThread());
                openedSocked.close(1000, "");
                openedSocked = null;
            }
        });

        createWebSocketConnection();
        return observable;
        */
    }

    // this used to be done automatically whenever the "subscriber list" was empty
    // this way is more discrete
    @Override
    public Completable disconnect() {
        return Completable.fromAction(() -> openedSocked.close(1000, ""));
    }

    private void createWebSocketConnection() {

        if (openedSocked != null) {
            throw new IllegalStateException("Already have connection to web socket");
        }

        Request.Builder requestBuilder = new Request.Builder()
                .url(mUri);

        addConnectionHeadersToBuilder(requestBuilder, mConnectHttpHeaders);

        openedSocked = mOkHttpClient.newWebSocket(requestBuilder.build(),
                new WebSocketListener() {
                    @Override
                    public void onOpen(WebSocket webSocket, Response response) {
                        LifecycleEvent openEvent = new LifecycleEvent(LifecycleEvent.Type.OPENED);

                        TreeMap<String, String> headersAsMap = headersAsMap(response);

                        openEvent.setHandshakeResponseHeaders(headersAsMap);
                        emitLifecycleEvent(openEvent);
                    }

                    @Override
                    public void onMessage(WebSocket webSocket, String text) {
                        emitMessage(text);
                    }

                    @Override
                    public void onMessage(WebSocket webSocket, ByteString bytes) {
                        emitMessage(bytes.utf8());
                    }

                    @Override
                    public void onClosed(WebSocket webSocket, int code, String reason) {
                        emitLifecycleEvent(new LifecycleEvent(LifecycleEvent.Type.CLOSED));
                        openedSocked = null;
                    }

                    @Override
                    public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                        emitLifecycleEvent(new LifecycleEvent(LifecycleEvent.Type.ERROR, new Exception(t)));
                    }
                }

        );
    }

    @Override
    public Observable<Void> send(String stompMessage) {
        // .create(onSubscribe) is deprecated because it's unsafe
        return Observable.fromCallable(() -> {
            if (openedSocked == null) {
                throw new IllegalStateException("Not connected yet");
            } else {
                Log.d(TAG, "Send STOMP message: " + stompMessage);
                openedSocked.send(stompMessage);
                return null;
            }
        });
    }

    @Override
    public Observable<LifecycleEvent> getLifecycleReceiver() {
        // Once again, opting to leave Subscriber tracking to Rx
        return mLifecycleStream;

        /*
        return Observable.<LifecycleEvent>create(subscriber -> {
            mLifecycleSubscribers.add(subscriber);

        }).doOnUnsubscribe(() -> {
            Iterator<Subscriber<? super LifecycleEvent>> iterator = mLifecycleSubscribers.iterator();
            while (iterator.hasNext()) {
                if (iterator.next().isUnsubscribed()) iterator.remove();
            }
        });
        */
    }

    private TreeMap<String, String> headersAsMap(Response response) {
        TreeMap<String, String> headersAsMap = new TreeMap<>();
        Headers headers = response.headers();
        for (String key : headers.names()) {
            headersAsMap.put(key, headers.get(key));
        }
        return headersAsMap;
    }

    private void addConnectionHeadersToBuilder(Request.Builder requestBuilder, Map<String, String> mConnectHttpHeaders) {
        for (Map.Entry<String, String> headerEntry : mConnectHttpHeaders.entrySet()) {
            requestBuilder.addHeader(headerEntry.getKey(), headerEntry.getValue());
        }
    }

    private void emitLifecycleEvent(LifecycleEvent lifecycleEvent) {
        Log.d(TAG, "Emit lifecycle event: " + lifecycleEvent.getType().name());
        // I know Subjects are discouraged, but I think this is way cleaner than before
        mLifecycleStream.onNext(lifecycleEvent);

        /*
        for (Subscriber<? super LifecycleEvent> subscriber : mLifecycleSubscribers) {
            subscriber.onNext(lifecycleEvent);
        }
        */
    }

    private void emitMessage(String stompMessage) {
        Log.d(TAG, "Emit STOMP message: " + stompMessage);
        mMessagesStream.onNext(stompMessage);

        /*
        for (Subscriber<? super String> subscriber : mMessagesSubscribers) {
            subscriber.onNext(stompMessage);
        }
        */
    }
}
