/*
 * Copyright (C) 2014 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import okhttp3.internal.NamedRunnable;
import okhttp3.internal.cache.CacheInterceptor;
import okhttp3.internal.connection.ConnectInterceptor;
import okhttp3.internal.connection.StreamAllocation;
import okhttp3.internal.http.BridgeInterceptor;
import okhttp3.internal.http.CallServerInterceptor;
import okhttp3.internal.http.RealInterceptorChain;
import okhttp3.internal.http.RetryAndFollowUpInterceptor;
import okhttp3.internal.platform.Platform;

import static okhttp3.internal.platform.Platform.INFO;

final class RealCall implements Call {
  private final OkHttpClient client;
  private final RetryAndFollowUpInterceptor retryAndFollowUpInterceptor;

  // Guarded by this.
  private boolean executed;

  /** The application's original request unadulterated by redirects or auth headers. */
  Request originalRequest;
  /**
   * 新建一个请求对象
   * @param client
   * @param originalRequest
   */
  protected RealCall(OkHttpClient client, Request originalRequest) {
    this.client = client;
    this.originalRequest = originalRequest;
    this.retryAndFollowUpInterceptor = new RetryAndFollowUpInterceptor(client);
  }

  @Override public Request request() {
    return originalRequest;
  }
  /**
   * RealCall的同步调用逻辑
   */
  @Override public Response execute() throws IOException {
    // 加入对象锁，避免多次执行
    synchronized (this) {
      if (executed) throw new IllegalStateException("Already Executed");
      executed = true;
    }
    // 我们可以看到调用了调度分发器，执行executed
    try {
      // client的dispatcher函数获取的是实例化时候的调取器
      // 调用executed方法
      client.dispatcher().executed(this);
      Response result = getResponseWithInterceptorChain();
      if (result == null) throw new IOException("Canceled");
      return result;
    } finally {
      client.dispatcher().finished(this);
    }
  }

  synchronized void setForWebSocket() {
    if (executed) throw new IllegalStateException("Already Executed");
    this.retryAndFollowUpInterceptor.setForWebSocket(true);
  }
  /**
   * RealCall的异步调用逻辑
   */
  @Override public void enqueue(Callback responseCallback) {
    synchronized (this) {
      if (executed) throw new IllegalStateException("Already Executed");
      executed = true;
    }
    // client的dispatcher函数获取的是实例化时候的调取器
    // 调用enqueue方法，我们看一下这个方法的实现
    client.dispatcher().enqueue(new AsyncCall(responseCallback));
  }

  @Override public void cancel() {
    retryAndFollowUpInterceptor.cancel();
  }

  @Override public synchronized boolean isExecuted() {
    return executed;
  }

  @Override public boolean isCanceled() {
    return retryAndFollowUpInterceptor.isCanceled();
  }

  StreamAllocation streamAllocation() {
    return retryAndFollowUpInterceptor.streamAllocation();
  }
  /**
   * 我们看一下AsyncCall的实现：AsyncCall是RealCall里面的一个内部类
   * 实现了NamedRunnable。这个其实就是可以为单独某次的请求设置线程的名称
   */
  final class AsyncCall extends NamedRunnable {
    private final Callback responseCallback;

    private AsyncCall(Callback responseCallback) {
      super("OkHttp %s", redactedUrl().toString());
      this.responseCallback = responseCallback;
    }

    String host() {
      return originalRequest.url().host();
    }

    Request request() {
      return originalRequest;
    }

    RealCall get() {
      return RealCall.this;
    }
    /**
     * 通过看execute的方法。这里就是异步任务执行的的根本方法
     * 
     */
    @Override protected void execute() {
      boolean signalledCallback = false;
      try {
        // 这个地方通过责任链模式，层层进行处理，最后将response返回
        // 那么很简单了，我们要看这里面怎么去请求Response
        Response response = getResponseWithInterceptorChain();
        // 即时前面所有的拦截器返回了Response。但是RealCall本身也有一个拦截器
        if (retryAndFollowUpInterceptor.isCanceled()) {
          signalledCallback = true;
          responseCallback.onFailure(RealCall.this, new IOException("Canceled"));
        } else {
          signalledCallback = true;
          responseCallback.onResponse(RealCall.this, response);
        }
      } catch (IOException e) {
        if (signalledCallback) {
          // Do not signal the callback twice!
          Platform.get().log(INFO, "Callback failure for " + toLoggableString(), e);
        } else {
          responseCallback.onFailure(RealCall.this, e);
        }
      } finally {
        client.dispatcher().finished(this);
      }
    }
  }

  /**
   * Returns a string that describes this call. Doesn't include a full URL as that might contain
   * sensitive information.
   */
  private String toLoggableString() {
    String string = retryAndFollowUpInterceptor.isCanceled() ? "canceled call" : "call";
    return string + " to " + redactedUrl();
  }

  HttpUrl redactedUrl() {
    return originalRequest.url().resolve("/...");
  }

  private Response getResponseWithInterceptorChain() throws IOException {
    // Build a full stack of interceptors.
    List<Interceptor> interceptors = new ArrayList<>();
    interceptors.addAll(client.interceptors());
    interceptors.add(retryAndFollowUpInterceptor);
    interceptors.add(new BridgeInterceptor(client.cookieJar()));
    interceptors.add(new CacheInterceptor(client.internalCache()));
    interceptors.add(new ConnectInterceptor(client));
    if (!retryAndFollowUpInterceptor.isForWebSocket()) {
      interceptors.addAll(client.networkInterceptors());
    }
    interceptors.add(new CallServerInterceptor(
        retryAndFollowUpInterceptor.isForWebSocket()));

    Interceptor.Chain chain = new RealInterceptorChain(
        interceptors, null, null, null, 0, originalRequest);
    return chain.proceed(originalRequest);
  }
}
