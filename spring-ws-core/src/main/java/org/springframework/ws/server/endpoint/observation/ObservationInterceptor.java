/*
 * Copyright 2005-2024 the original author or authors.
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
package org.springframework.ws.server.endpoint.observation;

import io.micrometer.common.util.internal.logging.WarnThenDebugLogger;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.ws.FaultAwareWebServiceMessage;
import org.springframework.ws.WebServiceMessage;
import org.springframework.ws.client.core.observation.RootElementSAXHandler;
import org.springframework.ws.context.MessageContext;
import org.springframework.ws.server.endpoint.interceptor.EndpointInterceptorAdapter;
import org.springframework.ws.soap.SoapMessage;
import org.springframework.ws.transport.HeadersAwareReceiverWebServiceConnection;
import org.springframework.ws.transport.TransportConstants;
import org.springframework.ws.transport.context.TransportContext;
import org.springframework.ws.transport.context.TransportContextHolder;
import org.springframework.ws.transport.http.HttpServletConnection;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import javax.xml.namespace.QName;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.Source;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamSource;
import java.io.IOException;

/**
 * Interceptor implementation that creates an observation for a WebService Endpoint.
 * @author Johan Kindgren
 */
public class ObservationInterceptor extends EndpointInterceptorAdapter {

    private final Log logger = LogFactory.getLog(getClass());

    private static final WarnThenDebugLogger WARN_THEN_DEBUG_LOGGER = new WarnThenDebugLogger(ObservationInterceptor.class);
    private static final String OBSERVATION_KEY = "observation";
    private static final QName UNKNOWN_Q_NAME = new QName("unknown", "unknow");
    private static final WebServiceEndpointConvention DEFAULT_CONVENTION = new DefaultWebServiceEndpointConvention();

    private final ObservationRegistry observationRegistry;
    private final WebServiceEndpointConvention customConvention;
    private final SAXParser saxParser;

    public ObservationInterceptor(
            @NonNull
            ObservationRegistry observationRegistry,
            @Nullable
            WebServiceEndpointConvention customConvention) {
        this.observationRegistry = observationRegistry;
        this.customConvention = customConvention;

        SAXParserFactory parserFactory = SAXParserFactory.newNSInstance();
        SAXParser parser = null;
        try {
            parser = parserFactory.newSAXParser();
        } catch (ParserConfigurationException | SAXException e) {
            logger.warn("Could not create SAX parser, observation keys for Root element can be reported as 'unknown'.", e);
        }
        saxParser = parser;
    }

    @Override
    public boolean handleRequest(MessageContext messageContext, Object endpoint) throws Exception {

        TransportContext transportContext = TransportContextHolder.getTransportContext();
        HeadersAwareReceiverWebServiceConnection connection =
                (HeadersAwareReceiverWebServiceConnection) transportContext.getConnection();

        Observation observation = EndpointObservationDocumentation.WEB_SERVICE_ENDPOINT.start(
                customConvention,
                DEFAULT_CONVENTION,
                () -> new WebServiceEndpointContext(connection),
                observationRegistry);

        messageContext.setProperty(OBSERVATION_KEY, observation);

        return true;
    }

    @Override
    public void afterCompletion(MessageContext messageContext, Object endpoint, @Nullable Exception ex) {

        Observation observation = (Observation) messageContext.getProperty(OBSERVATION_KEY);
        if (observation == null) {
            WARN_THEN_DEBUG_LOGGER.log("Missing expected Observation in messageContext; the request will not be observed.");
            return;
        }

        WebServiceEndpointContext context = (WebServiceEndpointContext) observation.getContext();

        WebServiceMessage request = messageContext.getRequest();
        WebServiceMessage response = messageContext.getResponse();

        if (request instanceof SoapMessage soapMessage) {

            Source source = soapMessage.getSoapBody().getPayloadSource();
            QName root = getRootElement(source);
            if (root != null) {
                context.setLocalPart(root.getLocalPart());
                context.setNamespace(root.getNamespaceURI());
            }
            String action = soapMessage.getSoapAction();
            if (!TransportConstants.EMPTY_SOAP_ACTION.equals(action)) {
                context.setSoapAction(soapMessage.getSoapAction());
            } else {
                context.setSoapAction("none");
            }
        }

        if (ex == null) {
            context.setOutcome("success");
        } else {
            context.setError(ex);
            context.setOutcome("fault");
        }

        if (response instanceof FaultAwareWebServiceMessage faultAwareResponse) {
            if (faultAwareResponse.hasFault()) {
                context.setOutcome("fault");
            }
        }

        TransportContext transportContext = TransportContextHolder.getTransportContext();
        HeadersAwareReceiverWebServiceConnection connection =
                (HeadersAwareReceiverWebServiceConnection) transportContext.getConnection();

        if (connection instanceof HttpServletConnection servletConnection) {
            HttpServletRequest servletRequest = servletConnection.getHttpServletRequest();
            String servletPath = servletRequest.getServletPath();
            String pathInfo = servletRequest.getPathInfo();

            if (pathInfo != null) {
                context.setContextualName("POST " + servletPath + "/{pathInfo}");
                context.setPath(servletPath + "/{pathInfo}");
                context.setPathInfo(pathInfo);
            } else {
                context.setContextualName("POST " + servletPath);
            }
        } else {
            context.setContextualName("POST");
        }

        observation.stop();
    }

    QName getRootElement(Source source) {
        if (source instanceof DOMSource) {
            Node root = ((DOMSource) source).getNode();
            return new QName(root.getNamespaceURI(), root.getLocalName());
        }
        if (source instanceof StreamSource) {
            if (saxParser == null) {
                WARN_THEN_DEBUG_LOGGER.log("SaxParser not available, reporting Root element as 'unknown'");
                return UNKNOWN_Q_NAME;
            }
            RootElementSAXHandler handler = new RootElementSAXHandler();
            try {
                saxParser.parse(((StreamSource) source).getInputStream(), handler);
                return handler.getRootElementName();
            } catch (SAXException | IOException e) {
                WARN_THEN_DEBUG_LOGGER.log("Exception while handling request, reporting Root element as 'unknown'", e);
                return UNKNOWN_Q_NAME;
            }
        }
        if (source instanceof SAXSource) {
            if (saxParser == null) {
                WARN_THEN_DEBUG_LOGGER.log("SaxParser not available, reporting Root element as 'unknown'");
                return UNKNOWN_Q_NAME;
            }
            RootElementSAXHandler handler = new RootElementSAXHandler();
            try {
                saxParser.parse(((SAXSource) source).getInputSource(), handler);
                return handler.getRootElementName();
            } catch (SAXException | IOException e) {
                WARN_THEN_DEBUG_LOGGER.log("Exception while handling request, reporting Root element as 'unknown'", e);
                return UNKNOWN_Q_NAME;
            }
        }
        return UNKNOWN_Q_NAME;
    }
}
