/*
 * Jigasi, the JItsi GAteway to SIP.
 *
 * Copyright @ 2015 Atlassian Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.jigasi.xmpp;

import net.java.sip.communicator.impl.protocol.jabber.extensions.rayo.RayoIqProvider.*;
import net.java.sip.communicator.util.*;
import org.jitsi.jigasi.*;
import org.jitsi.service.configuration.*;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.util.*;

/**
 *  Implementation of call control that is capable of utilizing Rayo
 *  XMPP protocol for the purpose of SIP gateway calls management.
 *
 * @author Damian Minkov
 * @author Nik Vaessen
 */
public class CallControl
{
    /**
     * The logger.
     */
    private final static Logger logger = Logger.getLogger(CallControl.class);

    /**
     * Name of 'header' attribute that hold JVB room name.
     */
    public static final String ROOM_NAME_HEADER = "JvbRoomName";

    /**
     * Optional header for specifying password required to enter MUC room.
     */
    public static final String ROOM_PASSWORD_HEADER = "JvbRoomPassword";

    /**
     * JID allowed to make outgoing SIP calls.
     */
    public static final String ALLOWED_JID_P_NAME
        = "org.jitsi.jigasi.ALLOWED_JID";

    /**
     * The {@link SipGateway} service which manages gateway sessions.
     */
    private SipGateway gateway;

    /**
     * The only JID that will be allowed to create outgoing SIP calls. If not
     * set then anybody is allowed to do so.
     */
    private String allowedJid;

    /**
     * Constructs new call control instance.
     * @param gateway the sip gateway instance.
     * @param config the config service instance.
     */
    public CallControl(SipGateway gateway, ConfigurationService config)
    {
        this.gateway = gateway;

        Boolean always_trust_mode = config.getBoolean(
            "net.java.sip.communicator.service.gui.ALWAYS_TRUST_MODE_ENABLED",
            false);
        if (always_trust_mode)
        {
            // Always trust mode - prevent failures because there's no GUI
            // to ask the user, but do we always want to trust so, in this
            // mode, the service is vulnerable to Man-In-The-Middle attacks.
            logger.warn(
                "Always trust in remote TLS certificates mode is enabled");
        }

        this.allowedJid = config.getString(ALLOWED_JID_P_NAME, null);

        if (allowedJid != null)
        {
            logger.info("JID allowed to make outgoing calls: " + allowedJid);
        }
    }

    /**
     * Handles an <tt>org.jivesoftware.smack.packet.IQ</tt> stanza of type
     * <tt>set</tt> which represents a request.
     *
     * @param iq the <tt>org.jivesoftware.smack.packet.IQ</tt> stanza of type
     * <tt>set</tt> which represents the request to handle
     * @param ctx the call context to process
     * @return an <tt>org.jivesoftware.smack.packet.IQ</tt> stanza which
     * represents the response to the specified request or <tt>null</tt> to
     * reply with <tt>feature-not-implemented</tt>
     * @throws Exception to reply with <tt>internal-server-error</tt> to the
     * specified request
     */
    public IQ handleIQ(IQ iq, CallContext ctx)
        throws Exception
    {
        try
        {
            String fromBareJid = StringUtils.parseBareAddress(iq.getFrom());
            if (allowedJid != null && !allowedJid.equals(fromBareJid))
            {
                return IQ.createErrorResponse(
                    iq,
                    new XMPPError(XMPPError.Condition.not_allowed));
            }
            else if (allowedJid == null)
            {
                logger.warn("Requests are not secured by JID filter!");
            }

            if (iq instanceof DialIq)
            {
                DialIq dialIq = (DialIq) iq;

                String from = dialIq.getSource();
                String to = dialIq.getDestination();
                ctx.setDestination(to);

                String roomName = dialIq.getHeader(ROOM_NAME_HEADER);
                ctx.setRoomName(roomName);

                String roomPassword = dialIq.getHeader(ROOM_PASSWORD_HEADER);
                ctx.setRoomPassword(roomPassword);
                if (roomName == null)
                    throw new RuntimeException("No JvbRoomName header found");

                logger.info(
                    "Got dial request " + from + " -> " + to
                        + " room: " + roomName);

                String callResource = ctx.getCallResource();

                gateway.createOutgoingCall(ctx);

                callResource = "xmpp:" + callResource;

                return RefIq.createResult(iq, callResource);
            }
            else if (iq instanceof HangUp)
            {
                HangUp hangUp = (HangUp) iq;

                String callResource = hangUp.getTo();

                SipGatewaySession session = gateway.getSession(callResource);

                if (session == null)
                    throw new RuntimeException(
                        "No gateway for call: " + callResource);

                session.hangUp();

                return IQ.createResultIQ(iq);
            }
            else
            {
                return null;
            }
        }
        catch (Exception e)
        {
            logger.error(e, e);
            throw e;
        }
    }
}
