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
   * 我们可以注意看这个同步锁的加载，
   * 保证我们的一个RealCall只调度发送一次请求
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

  /**
   * 我们看一下，OKhttp是怎么取消一个请求的
   */
  @Override 
  public void cancel() {
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
    @Override 
    protected void execute() {
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
        /// 重点是这个方法，当所有的网络请求执行完毕之后，都是执行client.dispatcher().finished(this)
        /// 这里面我们可以看到等待队列是怎么维护的？？
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

  /**
   * 这个方法是非常重要的，在线程池的子线程中来进行执行
   * 这个就是通过责任链的模式来进行我们的Response的请求
   * 
   * TODO 这个我们需要看一下，我们这些拦截器是随便插入吗？？还是有插入顺序的。
   * 
   * 实际上是有插入顺序的。也就是我们自定义的拦截器是放在顺序的前部。
   * 最终网络请求的的拦截器放在最后面
   * 
   * @return
   * @throws IOException
   */
  private Response getResponseWithInterceptorChain() throws IOException {
    // Build a full stack of interceptors.
    List<Interceptor> interceptors = new ArrayList<>();
    // 首先添加的是client里面我们自定义的拦截器
    interceptors.addAll(client.interceptors());
    // 添加retryAndFollowUpInterceptor。重试和重定向拦截器
    interceptors.add(retryAndFollowUpInterceptor);
    // 添加BridgeInterceptor。添加请求头的配置信息（主要就是Http请求的协议内容）
    interceptors.add(new BridgeInterceptor(client.cookieJar()));
    // 添加缓存的CacheInterceptor
    interceptors.add(new CacheInterceptor(client.internalCache()));
    // 添加连接的ConnectInterceptor。Socket连接池
    interceptors.add(new ConnectInterceptor(client));

    /// 添加网络重试和跟踪拦截器拦截器
    if (!retryAndFollowUpInterceptor.isForWebSocket()) {
      interceptors.addAll(client.networkInterceptors());
    }
    ///请求服务器拦截器
    interceptors.add(new CallServerInterceptor(
        retryAndFollowUpInterceptor.isForWebSocket()));

    // 实例化RealInterceptorChain连接器责任链里面的链式对象
    Interceptor.Chain chain = new RealInterceptorChain(
        interceptors, null, null, null, 0, originalRequest);
    // 调用链式对象的proceed
    return chain.proceed(originalRequest);
  }
}
