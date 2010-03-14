/*
 * $Id$
 * --------------------------------------------------------------------------------------
 * Copyright (c) MuleSource, Inc.  All rights reserved.  http://www.mulesource.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.module.management.agent;

import org.mule.AbstractAgent;
import org.mule.api.MuleException;
import org.mule.api.MuleRuntimeException;
import org.mule.api.agent.Agent;
import org.mule.api.context.notification.MuleContextNotificationListener;
import org.mule.api.lifecycle.InitialisationException;
import org.mule.api.model.Model;
import org.mule.api.service.Service;
import org.mule.api.transport.Connector;
import org.mule.api.transport.MessageReceiver;
import org.mule.config.i18n.CoreMessages;
import org.mule.context.notification.MuleContextNotification;
import org.mule.context.notification.NotificationException;
import org.mule.module.management.i18n.ManagementMessages;
import org.mule.module.management.mbean.ConnectorService;
import org.mule.module.management.mbean.ConnectorServiceMBean;
import org.mule.module.management.mbean.EndpointService;
import org.mule.module.management.mbean.EndpointServiceMBean;
import org.mule.module.management.mbean.ModelService;
import org.mule.module.management.mbean.ModelServiceMBean;
import org.mule.module.management.mbean.MuleConfigurationService;
import org.mule.module.management.mbean.MuleConfigurationServiceMBean;
import org.mule.module.management.mbean.MuleService;
import org.mule.module.management.mbean.MuleServiceMBean;
import org.mule.module.management.mbean.ServiceService;
import org.mule.module.management.mbean.ServiceServiceMBean;
import org.mule.module.management.mbean.StatisticsService;
import org.mule.module.management.mbean.StatisticsServiceMBean;
import org.mule.module.management.support.AutoDiscoveryJmxSupportFactory;
import org.mule.module.management.support.JmxSupport;
import org.mule.module.management.support.JmxSupportFactory;
import org.mule.module.management.support.SimplePasswordJmxAuthenticator;
import org.mule.transport.AbstractConnector;
import org.mule.util.ClassUtils;
import org.mule.util.StringUtils;

import java.lang.management.ManagementFactory;
import java.net.URI;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.ExportException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.management.InstanceAlreadyExistsException;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.MalformedObjectNameException;
import javax.management.NotCompliantMBeanException;
import javax.management.ObjectName;
import javax.management.remote.JMXAuthenticator;
import javax.management.remote.JMXConnectorServer;
import javax.management.remote.JMXConnectorServerFactory;
import javax.management.remote.JMXServiceURL;
import javax.management.remote.rmi.RMIConnectorServer;

import edu.emory.mathcs.backport.java.util.concurrent.atomic.AtomicBoolean;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * <code>JmxAgent</code> registers Mule Jmx management beans with an MBean server.
 */
public class JmxAgent extends AbstractAgent
{
    public static final String NAME = "jmx-agent";

    public static final String DEFAULT_REMOTING_URI = "service:jmx:rmi:///jndi/rmi://localhost:1099/server";
    // populated with values below in a static initializer
    public static final Map<String, String> DEFAULT_CONNECTOR_SERVER_PROPERTIES;

    /**
     * Default JMX Authenticator to use for securing remote access.
     */
    public static final String DEFAULT_JMX_AUTHENTICATOR = SimplePasswordJmxAuthenticator.class.getName();

    /**
     * Logger used by this class
     */
    protected static final Log logger = LogFactory.getLog(JmxAgent.class);

    /**
     * Should MBeanServer be discovered.
     */
    protected boolean locateServer = true;

    // don't create mbean server by default, use a platform mbean server
    private boolean createServer = false;
    private String connectorServerUrl;
    private MBeanServer mBeanServer;
    private JMXConnectorServer connectorServer;
    private Map<String, Object> connectorServerProperties = null;
    private boolean enableStatistics = true;
    private final AtomicBoolean serverCreated = new AtomicBoolean(false);
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    private JmxSupportFactory jmxSupportFactory = AutoDiscoveryJmxSupportFactory.getInstance();
    private JmxSupport jmxSupport = jmxSupportFactory.getJmxSupport();

    //Used is RMI is being used
    private Registry rmiRegistry;
    private boolean createRmiRegistry = true;
    /**
     * Username/password combinations for JMX Remoting authentication.
     */
    private Map<String, String> credentials = new HashMap<String, String>();

    static
    {
        Map<String, String> props = new HashMap<String, String>(1);
        props.put(RMIConnectorServer.JNDI_REBIND_ATTRIBUTE, "true");
        DEFAULT_CONNECTOR_SERVER_PROPERTIES = Collections.unmodifiableMap(props);
    }

    public JmxAgent()
    {
        super(NAME);
        connectorServerProperties = new HashMap<String, Object>(DEFAULT_CONNECTOR_SERVER_PROPERTIES);
    }

    @Override
    public String getDescription()
    {
        if (connectorServerUrl != null)
        {
            return name + ": " + connectorServerUrl;
        }
        else
        {
            return "JMX Agent";
        }
    }

    /**
     * The JmxAgent needs a RmiRegistryAgent to be started before it can properly work.
     */
    @Override
    public List<Class<? extends Agent>> getDependentAgents()
    {
        // use an extra var to explicitly specify the correct generics type
        List<Class<? extends Agent>> list = new ArrayList<Class<? extends Agent>>(1);
        list.add(RmiRegistryAgent.class);
        return list;
    }

    /**
     * {@inheritDoc}
     */
    public void initialise() throws InitialisationException
    {
        if (initialized.get())
        {
            return;
        }

        try
        {
            Object agent = muleContext.getRegistry().lookupObject(this.getClass());
            // if we find ourselves, but not initialized yet - proceed with init, otherwise return
            if (agent == this && this.initialized.get())
            {
                if (logger.isDebugEnabled())
                {
                    logger.debug("Found an existing JMX agent in the registry, we're done here.");
                }
                return;
            }
            //initRMI();
        }
        catch (Exception e)
        {
            throw new InitialisationException(e, this);
        }


        if (mBeanServer == null && createServer)
        {
            // here we create a new mbean server, not using a platform one
            mBeanServer = MBeanServerFactory.createMBeanServer();
            serverCreated.set(true);
        }

        if (mBeanServer == null && locateServer)
        {
            mBeanServer = ManagementFactory.getPlatformMBeanServer();
        }

        if (mBeanServer == null)
        {
            throw new InitialisationException(ManagementMessages.cannotLocateOrCreateServer(), this);
        }

        if (StringUtils.isBlank(muleContext.getConfiguration().getId()))
        {
            // TODO i18n the message properly
            throw new IllegalArgumentException(
                    "Manager ID is mandatory when running with JmxAgent. Give your Mule configuration a valid ID.");
        }

        try
        {
            // We need to register all the services once the server has initialised
            muleContext.registerListener(new MuleContextStartedListener());
            // and unregister once context stopped
            muleContext.registerListener(new MuleContextStoppedListener());
        }
        catch (NotificationException e)
        {
            throw new InitialisationException(e, this);
        }
        initialized.compareAndSet(false, true);
    }

    protected void initRMI() throws Exception
    {
        String connectUri = (connectorServerUrl!=null ? connectorServerUrl : DEFAULT_REMOTING_URI);
        if (connectUri.contains("jmx:rmi"))
        {
            int i = connectUri.lastIndexOf("rmi://");
            URI uri = new URI(connectUri.substring(i));
            if (rmiRegistry == null)
            {
                try
                {
                    if (isCreateRmiRegistry())
                    {
                        try
                        {
                            rmiRegistry = LocateRegistry.createRegistry(uri.getPort());
                        }
                        catch (ExportException e)
                        {
                            logger.info("Registry on " + uri  + " already bound. Attempting to use that instead");
                            rmiRegistry = LocateRegistry.getRegistry(uri.getHost(), uri.getPort());
                        }
                    }
                    else
                    {
                        rmiRegistry = LocateRegistry.getRegistry(uri.getHost(), uri.getPort());
                    }
                }
                catch (RemoteException e)
                {
                    throw new InitialisationException(e, this);
                }
            }
        }

    }

    public void start() throws MuleException
    {
        try
        {
            initRMI();
            logger.info("Creating and starting JMX agent connector Server");
            if (connectorServerUrl != null)
            {
                JMXServiceURL url = new JMXServiceURL(connectorServerUrl);
                if (connectorServerProperties == null)
                {
                    connectorServerProperties = new HashMap<String, Object>(DEFAULT_CONNECTOR_SERVER_PROPERTIES);
                }
                // TODO custom authenticator may have its own security config,
                // refactor
                if (!credentials.isEmpty())
                {
                    JMXAuthenticator jmxAuthenticator = (JMXAuthenticator) ClassUtils.instanciateClass(DEFAULT_JMX_AUTHENTICATOR);
                    // TODO support for custom authenticators
                    ((SimplePasswordJmxAuthenticator) jmxAuthenticator).setCredentials(credentials);
                    connectorServerProperties.put(JMXConnectorServer.AUTHENTICATOR, jmxAuthenticator);
                }
                connectorServer = JMXConnectorServerFactory.newJMXConnectorServer(url,
                        connectorServerProperties,
                        mBeanServer);
                connectorServer.start();
            }
        }
        catch (ExportException e)
        {
            throw new JmxManagementException(CoreMessages.failedToStart("Jmx Agent"), e);
        }
        catch (Exception e)
        {
            throw new JmxManagementException(CoreMessages.failedToStart("Jmx Agent"), e);
        }
    }

    public void stop() throws MuleException
    {
        if (connectorServer != null)
        {
            try
            {
                connectorServer.stop();
            }
            catch (Exception e)
            {
                throw new JmxManagementException(CoreMessages.failedToStop("Jmx Connector"), e);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    public void dispose()
    {
        unregisterMBeansIfNecessary();
        if (serverCreated.get())
        {
            MBeanServerFactory.releaseMBeanServer(mBeanServer);
        }
        mBeanServer = null;
        serverCreated.compareAndSet(true, false);
        initialized.set(false);
    }

    /**
     * {@inheritDoc}
     */
    public void registered()
    {
        // nothing to do
    }

    /**
     * {@inheritDoc}
     */
    public void unregistered()
    {
        // nothing to do
    }

    /**
     * Register a Java Service Wrapper agent.
     *
     * @throws MuleException if registration failed
     */
    protected void registerWrapperService() throws MuleException
    {
        // WrapperManager to support restarts
        final WrapperManagerAgent wmAgent = new WrapperManagerAgent();
        if (muleContext.getRegistry().lookupAgent(wmAgent.getName()) == null)
        {
            muleContext.getRegistry().registerAgent(wmAgent);
        }
    }

    protected void registerStatisticsService() throws NotCompliantMBeanException, MBeanRegistrationException,
            InstanceAlreadyExistsException, MalformedObjectNameException
    {
        ObjectName on = jmxSupport.getObjectName(String.format("%s:%s", jmxSupport.getDomainName(muleContext), StatisticsServiceMBean.DEFAULT_JMX_NAME));
        StatisticsService mBean = new StatisticsService();
        mBean.setMuleContext(muleContext);
        mBean.setEnabled(isEnableStatistics());
        logger.debug("Registering statistics with name: " + on);
        mBeanServer.registerMBean(mBean, on);
    }

    protected void registerModelServices() throws NotCompliantMBeanException, MBeanRegistrationException,
            InstanceAlreadyExistsException, MalformedObjectNameException
    {
        for (Model model : muleContext.getRegistry().lookupObjects(Model.class))
        {
            ModelServiceMBean serviceMBean = new ModelService(model);
            String rawName = serviceMBean.getName() + "(" + serviceMBean.getType() + ")";
            String name = jmxSupport.escape(rawName);
            final String jmxName = String.format("%s:%s%s", jmxSupport.getDomainName(muleContext), ModelServiceMBean.DEFAULT_JMX_NAME_PREFIX, name);
            ObjectName on = jmxSupport.getObjectName(jmxName);
            logger.debug("Registering model with name: " + on);
            mBeanServer.registerMBean(serviceMBean, on);
        }
    }

    protected void registerMuleService() throws NotCompliantMBeanException, MBeanRegistrationException,
            InstanceAlreadyExistsException, MalformedObjectNameException
    {
        ObjectName on = jmxSupport.getObjectName(String.format("%s:%s", jmxSupport.getDomainName(muleContext), MuleServiceMBean.DEFAULT_JMX_NAME));
        MuleServiceMBean serviceMBean = new MuleService(muleContext);
        logger.debug("Registering mule with name: " + on);
        mBeanServer.registerMBean(serviceMBean, on);
    }

    protected void registerConfigurationService() throws NotCompliantMBeanException, MBeanRegistrationException,
            InstanceAlreadyExistsException, MalformedObjectNameException
    {
        ObjectName on = jmxSupport.getObjectName(String.format("%s:%s", jmxSupport.getDomainName(muleContext), MuleConfigurationServiceMBean.DEFAULT_JMX_NAME));
        MuleConfigurationServiceMBean serviceMBean = new MuleConfigurationService(muleContext.getConfiguration());
        logger.debug("Registering configuration with name: " + on);
        mBeanServer.registerMBean(serviceMBean, on);
    }

    protected void registerServiceServices() throws NotCompliantMBeanException, MBeanRegistrationException,
            InstanceAlreadyExistsException, MalformedObjectNameException
    {
        for (Service service : muleContext.getRegistry().lookupObjects(Service.class))
        {
            final String rawName = service.getName();
            final String name = jmxSupport.escape(rawName);
            final String jmxName = String.format("%s:%s%s", jmxSupport.getDomainName(muleContext), ServiceServiceMBean.DEFAULT_JMX_NAME_PREFIX, name);
            ObjectName on = jmxSupport.getObjectName(jmxName);
            ServiceServiceMBean serviceMBean = new ServiceService(rawName, muleContext);
            logger.debug("Registering service with name: " + on);
            mBeanServer.registerMBean(serviceMBean, on);
        }
    }

    protected void registerEndpointServices() throws NotCompliantMBeanException, MBeanRegistrationException,
            InstanceAlreadyExistsException, MalformedObjectNameException
    {
        for (Connector connector : muleContext.getRegistry().lookupObjects(Connector.class))
        {
            if (connector instanceof AbstractConnector)
            {
                for (MessageReceiver messageReceiver : ((AbstractConnector) connector).getReceivers().values())
                {
                    EndpointServiceMBean mBean = new EndpointService(messageReceiver);

                    String fullName = buildFullyQualifiedEndpointName(mBean, connector);
                    if (logger.isInfoEnabled())
                    {
                        logger.info("Attempting to register service with name: " + fullName);
                    }

                    ObjectName on = jmxSupport.getObjectName(fullName);
                    mBeanServer.registerMBean(mBean, on);
                    if (logger.isInfoEnabled())
                    {
                        logger.info("Registered Endpoint Service with name: " + on);
                    }
                }
            }
            else
            {
                logger.warn("Connector: " + connector
                        + " is not an istance of AbstractConnector, cannot obtain Endpoint MBeans from it");
            }
        }
    }

    protected String buildFullyQualifiedEndpointName(EndpointServiceMBean mBean, Connector connector)
    {
        String rawName = jmxSupport.escape(mBean.getName());

        StringBuilder fullName = new StringBuilder(128);
        fullName.append(jmxSupport.getDomainName(muleContext));
        fullName.append(":type=org.mule.Endpoint,service=");
        fullName.append(jmxSupport.escape(mBean.getComponentName()));
        fullName.append(",connector=");
        fullName.append(connector.getName());
        fullName.append(",name=");
        fullName.append(rawName);
        return fullName.toString();
    }

    protected void registerConnectorServices() throws
            MalformedObjectNameException,
            NotCompliantMBeanException,
            MBeanRegistrationException,
            InstanceAlreadyExistsException
    {
        for (Connector connector : muleContext.getRegistry().lookupObjects(Connector.class))
        {
            ConnectorServiceMBean mBean = new ConnectorService(connector);
            final String rawName = mBean.getName();
            final String name = jmxSupport.escape(rawName);
            final String jmxName = String.format("%s:%s%s", jmxSupport.getDomainName(muleContext), ConnectorServiceMBean.DEFAULT_JMX_NAME_PREFIX, name);
            if (logger.isDebugEnabled())
            {
                logger.debug("Attempting to register service with name: " + jmxName);
            }
            ObjectName oName = jmxSupport.getObjectName(jmxName);
            mBeanServer.registerMBean(mBean, oName);
            logger.info("Registered Connector Service with name " + oName);
        }
    }

    public boolean isCreateServer()
    {
        return createServer;
    }

    public void setCreateServer(boolean createServer)
    {
        this.createServer = createServer;
    }

    public boolean isLocateServer()
    {
        return locateServer;
    }

    public void setLocateServer(boolean locateServer)
    {
        this.locateServer = locateServer;
    }

    public String getConnectorServerUrl()
    {
        return connectorServerUrl;
    }

    public void setConnectorServerUrl(String connectorServerUrl)
    {
        this.connectorServerUrl = connectorServerUrl;
    }

    public boolean isEnableStatistics()
    {
        return enableStatistics;
    }

    public void setEnableStatistics(boolean enableStatistics)
    {
        this.enableStatistics = enableStatistics;
    }

    public MBeanServer getMBeanServer()
    {
        return mBeanServer;
    }

    public void setMBeanServer(MBeanServer mBeanServer)
    {
        this.mBeanServer = mBeanServer;
    }

    public Map<String, Object> getConnectorServerProperties()
    {
        return connectorServerProperties;
    }

    /**
     * Setter for property 'connectorServerProperties'. Set to {@code null} to use defaults ({@link
     * #DEFAULT_CONNECTOR_SERVER_PROPERTIES}). Pass in an empty map to use no parameters.
     * Passing a non-empty map will replace defaults.
     *
     * @param connectorServerProperties Value to set for property 'connectorServerProperties'.
     */
    public void setConnectorServerProperties(Map<String, Object> connectorServerProperties)
    {
        this.connectorServerProperties = connectorServerProperties;
    }

    public JmxSupportFactory getJmxSupportFactory()
    {
        return jmxSupportFactory;
    }

    public void setJmxSupportFactory(JmxSupportFactory jmxSupportFactory)
    {
        this.jmxSupportFactory = jmxSupportFactory;
    }


    /**
     * Setter for property 'credentials'.
     *
     * @param newCredentials Value to set for property 'credentials'.
     */
    public void setCredentials(final Map<String, String> newCredentials)
    {
        this.credentials.clear();
        if (newCredentials != null && !newCredentials.isEmpty())
        {
            this.credentials.putAll(newCredentials);
        }
    }

    protected void unregisterMBeansIfNecessary()
    {
        if (mBeanServer == null)
        {
            return;
        }

        try
        {
            ObjectName query = jmxSupport.getObjectName(jmxSupport.getDomainName(muleContext) + ":*");
            Set<ObjectName> mbeans = mBeanServer.queryNames(query, null);
            while (!mbeans.isEmpty())
            {
                ObjectName name = mbeans.iterator().next();
                try
                {
                    mBeanServer.unregisterMBean(name);
                }
                catch (Exception e)
                {
                    logger.warn(String.format("Failed to unregister MBean: %s. Error is: %s", name, e.getMessage()));
                }

                // query mbeans again, as some mbeans have cascaded unregister operations,
                // this prevents duplicate unregister attempts
                mbeans = mBeanServer.queryNames(query, null);
            }
        }
        catch (MalformedObjectNameException e)
        {
            logger.warn("Failed to create ObjectName query", e);
        }
    }

    public Registry getRmiRegistry()
    {
        return rmiRegistry;
    }

    public void setRmiRegistry(Registry rmiRegistry)
    {
        this.rmiRegistry = rmiRegistry;
    }

    public boolean isCreateRmiRegistry()
    {
        return createRmiRegistry;
    }

    public void setCreateRmiRegistry(boolean createRmiRegistry)
    {
        this.createRmiRegistry = createRmiRegistry;
    }

    protected class MuleContextStartedListener implements MuleContextNotificationListener<MuleContextNotification>
    {

        public void onNotification(MuleContextNotification notification)
        {
            if (notification.getAction() == MuleContextNotification.CONTEXT_STARTED)
            {
                try
                {
                    registerWrapperService();
                    registerStatisticsService();
                    registerMuleService();
                    registerConfigurationService();
                    registerModelServices();
                    registerServiceServices();
                    registerEndpointServices();
                    registerConnectorServices();
                }
                catch (Exception e)
                {
                    throw new MuleRuntimeException(CoreMessages.objectFailedToInitialise("MBeans"), e);
                }
            }
        }
    }

    protected class MuleContextStoppedListener implements MuleContextNotificationListener<MuleContextNotification>
    {

        public void onNotification(MuleContextNotification notification)
        {
            if (notification.getAction() == MuleContextNotification.CONTEXT_STOPPED)
            {
                unregisterMBeansIfNecessary();
            }
        }
    }
}
