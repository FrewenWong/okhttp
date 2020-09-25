/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package okhttp3.internal.connection;

import java.io.IOException;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.internal.http.HttpStream;
import okhttp3.internal.http.RealInterceptorChain;
/**
 * ConnectInterceptor主要是用于建立连接，并再连接成功后将流封装成对象传递给下一个拦截器CallServerInterceptor与远端进行读写操作。
 * 
 * 这个过程中会涉及比较多的类我们简述下每个类的作用
 * 
 * StreamAllocation：类似一个工厂用来创建连接RealConnection和与远端通信的流的封装对象HttpCodec
 * ConnectionPool：连接池用来存储可用的连接，在条件符合的情况下进行连接复用
 * HttpStream：对输入输出流的封装对象，对于http1和2实现不同
 * RealConnection：对tcp连接的封装
 * 
 */
/** Opens a connection to the target server and proceeds to the next interceptor. */
public final class ConnectInterceptor implements Interceptor {
  public final OkHttpClient client;

  public ConnectInterceptor(OkHttpClient client) {
    this.client = client;
  }

  @Override public Response intercept(Chain chain) throws IOException {
    /// 获取到RealInterceptorChain
    RealInterceptorChain realChain = (RealInterceptorChain) chain;
    // 获取到网络请求的Request
    Request request = realChain.request();

    //// 这个StreamAllocation进行管理Http请求的SOcke
    StreamAllocation streamAllocation = realChain.streamAllocation();

    // We need the network to satisfy this request. Possibly for validating a conditional GET.
    boolean doExtensiveHealthChecks = !request.method().equals("GET");
    HttpStream httpStream = streamAllocation.newStream(client, doExtensiveHealthChecks);

    /// StreamAllocation#connection()拿到连接对象RealConnection
    // 然后将它们传递给下一个拦截器。
    RealConnection connection = streamAllocation.connection();

    return realChain.proceed(request, streamAllocation, httpStream, connection);
  }
}
