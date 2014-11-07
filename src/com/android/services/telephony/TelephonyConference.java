/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.services.telephony;

import android.telecom.PhoneCapabilities;
import android.telecom.Conference;
import android.telecom.Connection;
import android.telecom.PhoneAccountHandle;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallStateException;
import com.android.internal.telephony.Phone;

import java.util.List;

/**
 * TelephonyConnection-based conference call for GSM conferences and IMS conferences (which may
 * be either GSM-based or CDMA-based).
 */
public class TelephonyConference extends Conference {

    /**
     * When {@code true}, indicates that conference participant information from an IMS conference
     * event package has been received.
     */
    private boolean mParticipantsReceived = false;

    public TelephonyConference(PhoneAccountHandle phoneAccount) {
        super(phoneAccount);
        setCapabilities(
                PhoneCapabilities.ADD_CALL |
                PhoneCapabilities.SUPPORT_HOLD |
                PhoneCapabilities.HOLD |
                PhoneCapabilities.MUTE |
                PhoneCapabilities.MANAGE_CONFERENCE);
        setActive();
    }

    /**
     * Invoked when the Conference and all it's {@link Connection}s should be disconnected.
     */
    @Override
    public void onDisconnect() {
        for (Connection connection : getConnections()) {
            Call call = getMultipartyCallForConnection(connection, "onDisconnect");
            if (call != null) {
                Log.d(this, "Found multiparty call to hangup for conference.");
                try {
                    call.hangup();
                    break;
                } catch (CallStateException e) {
                    Log.e(this, e, "Exception thrown trying to hangup conference");
                }
            }
        }
    }

    /**
     * Invoked when the specified {@link Connection} should be separated from the conference call.
     *
     * @param connection The connection to separate.
     */
    @Override
    public void onSeparate(Connection connection) {
        com.android.internal.telephony.Connection radioConnection =
                getOriginalConnection(connection);
        try {
            radioConnection.separate();
        } catch (CallStateException e) {
            Log.e(this, e, "Exception thrown trying to separate a conference call");
        }
    }

    @Override
    public void onMerge(Connection connection) {
        try {
            Phone phone = ((TelephonyConnection) connection).getPhone();
            if (phone != null) {
                phone.conference();
            }
        } catch (CallStateException e) {
            Log.e(this, e, "Exception thrown trying to merge call into a conference");
        }
    }

    /**
     * Invoked when the conference should be put on hold.
     */
    @Override
    public void onHold() {
        final TelephonyConnection connection = getFirstConnection();
        if (connection != null) {
            connection.performHold();
        }
    }

    /**
     * Invoked when the conference should be moved from hold to active.
     */
    @Override
    public void onUnhold() {
        final TelephonyConnection connection = getFirstConnection();
        if (connection != null) {
            connection.performUnhold();
        }
    }

    @Override
    public void onPlayDtmfTone(char c) {
        final TelephonyConnection connection = getFirstConnection();
        if (connection != null) {
            connection.onPlayDtmfTone(c);
        }
    }

    @Override
    public void onStopDtmfTone() {
        final TelephonyConnection connection = getFirstConnection();
        if (connection != null) {
            connection.onStopDtmfTone();
        }
    }

    @Override
    public void onConnectionAdded(Connection connection) {
        // If the conference was an IMS connection currently or before, disable MANAGE_CONFERENCE
        // as the default behavior. If there is a conference event package, this may be overridden.
        // If a conference event package was received, do not attempt to remove manage conference.
        if (connection instanceof TelephonyConnection &&
                ((TelephonyConnection) connection).wasImsConnection() &&
                !mParticipantsReceived) {
            int capabilities = getCapabilities();
            if (PhoneCapabilities.can(capabilities, PhoneCapabilities.MANAGE_CONFERENCE)) {
                int newCapabilities =
                        PhoneCapabilities.remove(capabilities, PhoneCapabilities.MANAGE_CONFERENCE);
                setCapabilities(newCapabilities);
            }
        }
    }

    @Override
    public Connection getPrimaryConnection() {
        // Default to the first connection.
        Connection primaryConnection = getConnections().get(0);

        // Otherwise look for a connection where the radio connection states it is multiparty.
        for (Connection connection : getConnections()) {
            com.android.internal.telephony.Connection radioConnection =
                    getOriginalConnection(connection);

            if (radioConnection != null && radioConnection.isMultiparty()) {
                primaryConnection = connection;
                break;
            }
        }

        return primaryConnection;
    }

    private Call getMultipartyCallForConnection(Connection connection, String tag) {
        com.android.internal.telephony.Connection radioConnection =
                getOriginalConnection(connection);
        if (radioConnection != null) {
            Call call = radioConnection.getCall();
            if (call != null && call.isMultiparty()) {
                return call;
            }
        }
        return null;
    }

    private com.android.internal.telephony.Connection getOriginalConnection(
            Connection connection) {

        if (connection instanceof TelephonyConnection) {
            return ((TelephonyConnection) connection).getOriginalConnection();
        } else {
            return null;
        }
    }

    private TelephonyConnection getFirstConnection() {
        final List<Connection> connections = getConnections();
        if (connections.isEmpty()) {
            return null;
        }
        return (TelephonyConnection) connections.get(0);
    }

    /**
     * Flags the conference to indicate that a conference event package has been received and there
     * is now participant data present which would permit conference management.
     */
    public void setParticipantsReceived() {
        if (!mParticipantsReceived) {
            int capabilities = getCapabilities();
            capabilities |= PhoneCapabilities.MANAGE_CONFERENCE;
            setCapabilities(capabilities);
        }
        mParticipantsReceived = true;
    }
}
