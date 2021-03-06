/**
 * Copyright 2016 LinkedIn Corp. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package com.github.ambry.rest;

import com.github.ambry.router.AsyncWritableChannel;
import com.github.ambry.router.Callback;
import com.github.ambry.router.ReadableStreamChannel;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import javax.net.ssl.SSLSession;


/**
 * RestRequest represents a HTTP request as a generic object that can be understood by all the layers in a RESTful
 * frontend.
 * <p/>
 * It contains metadata about the request including the {@link RestMethod} desired, the original URI and associated
 * arguments (in the form of parameters or headers). It also contains all the content associated with the request
 * and this content can be streamed out through the read operations.
 * <p/>
 * Implementations are expected to be thread-safe.
 * <p/>
 * NOTE: Most of the operations performed in the REST front end are async. Therefore any reference counted objects
 * inside an implementation of this interface have to be retained for the life of the object. They can be released on
 * {@link #close()}.
 */
public interface RestRequest extends ReadableStreamChannel {
  /**
   * Gets the generic {@link RestMethod} that this request desires.
   * @return RestMethod the {@link RestMethod} defined by the request.
   */
  public RestMethod getRestMethod();

  /**
   * Return the path (the parts of the URI after the domain excluding query parameters).
   * @return path String that represents part of the uri excluding domain and query parameters.
   */
  public String getPath();

  /**
   * Return the request URI.
   * @return the URI defined by the request.
   */
  public String getUri();

  /**
   * Gets all the arguments passed as a part of the request.
   * <p/>
   * The implementation can decide what constitute as arguments. It can be specific parts of the URI, query parameters,
   * header values etc. or a combination of any of these.
   * @return the arguments and their values (if any) as a map.
   */
  public Map<String, Object> getArgs();

  /**
   * If this request was over HTTPS, gets the {@link SSLSession} associated with the request.
   * @return The {@link SSLSession} for the request and response, or {@code null} if SSL was not used.
   */
  public SSLSession getSSLSession();

  /**
   * Prepares the request for reading.
   * <p/>
   * Any CPU bound tasks (decoding, decryption) can be performed in this method as it is expected to be called in a CPU
   * bound thread. Calling this from an I/O bound thread will impact throughput.
   * @throws RestServiceException if request channel is closed or if the request could not be prepared for reading.
   */
  public void prepare() throws RestServiceException;

  /**
   * Closes this request channel and releases all of the resources associated with it. Also records some metrics via
   * the {@link RestRequestMetricsTracker} instance attached to this RestRequest.
   * <p/>
   * Expected to be called once the request is "completed" i.e. either a response is completely sent out or an error
   * state is reached from which a response cannot be sent.
   * <p/>
   * {@inheritDoc}
   * @throws IOException if there is an I/O error while closing the request channel.
   */
  @Override
  public void close() throws IOException;

  /**
   * Gets the {@link RestRequestMetricsTracker} instance attached to this RestRequest.
   * @return the {@link RestRequestMetricsTracker} instance attached to this RestRequest.
   */
  public RestRequestMetricsTracker getMetricsTracker();

  /**
   * Set the digest algorithm to use on the data that is being streamed from the request. Once the data is emptied,
   * the digest can be obtained via {@link #getDigest()}.
   * <p/>
   * This function is ideally called before {@link #readInto(AsyncWritableChannel, Callback)}. After a call to
   * {@link #readInto(AsyncWritableChannel, Callback)}, some content may have been consumed and getting a digest may no
   * longer be possible. The safety of doing otherwise depends on the implementation.
   * @param digestAlgorithm the digest algorithm to use.
   * @throws NoSuchAlgorithmException if the {@code digestAlgorithm} does not exist or is not supported.
   * @throws IllegalStateException if {@link #readInto(AsyncWritableChannel, Callback)} has already been called.
   */
  public void setDigestAlgorithm(String digestAlgorithm) throws NoSuchAlgorithmException;

  /**
   * Gets the digest as specified by the digest algorithm set through {@link #setDigestAlgorithm(String)}. If none was
   * set, returns {@code null}.
   * <p/>
   * This function is ideally called after the data is emptied completely. Otherwise, the complete digest may not
   * have been calculated yet. The safety of doing otherwise depends on the implementation.
   * <p/>
   * "Emptying the data" refers to awaiting on the future or getting the callback after a
   * {@link #readInto(AsyncWritableChannel, Callback)} call.
   * @return the digest as computed by the digest algorithm set through {@link #setDigestAlgorithm(String)}. If none
   * was set, {@code null}.
   * @throws IllegalStateException if called before the data has been emptied.
   */
  public byte[] getDigest();
}
