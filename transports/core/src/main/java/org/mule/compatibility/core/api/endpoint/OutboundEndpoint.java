/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.compatibility.core.api.endpoint;

import org.mule.runtime.core.api.construct.FlowConstruct;
import org.mule.runtime.core.api.construct.FlowConstructAware;
import org.mule.runtime.core.api.exception.MessagingExceptionHandlerAware;
import org.mule.runtime.core.api.processor.Processor;
import org.mule.runtime.core.api.transport.LegacyOutboundEndpoint;

import java.util.List;

/**
 * @deprecated Transport infrastructure is deprecated.
 */
@Deprecated
public interface OutboundEndpoint
    extends ImmutableEndpoint, FlowConstructAware, MessagingExceptionHandlerAware,
    LegacyOutboundEndpoint, Processor {

  /**
   * @return a list of properties which should be carried over from the request message to the response message in the case of a
   *         synchronous call.
   */
  List<String> getResponseProperties();

  FlowConstruct getFlowConstruct();

  /**
   * @return true if the destination is compute in every request, false if the destination is always the same.
   */
  boolean isDynamic();
}


