// Copyright 2021 - Nym Technologies SA <contact@nymtech.net>
// SPDX-License-Identifier: Apache-2.0

// JS: I personally don't like this name very much, but could not think of anything better.
// I will gladly take any suggestions on how to rename this.

use crate::error::GatewayClientError;
use crate::GatewayPacketRouter;
use futures::channel::mpsc;
use nym_task::ShutdownToken;

pub type MixnetMessageSender = mpsc::UnboundedSender<Vec<Vec<u8>>>;
pub type MixnetMessageReceiver = mpsc::UnboundedReceiver<Vec<Vec<u8>>>;

pub type AcknowledgementSender = mpsc::UnboundedSender<Vec<Vec<u8>>>;
pub type AcknowledgementReceiver = mpsc::UnboundedReceiver<Vec<Vec<u8>>>;

#[derive(Clone, Debug)]
pub struct PacketRouter {
    ack_sender: AcknowledgementSender,
    mixnet_message_sender: MixnetMessageSender,
    #[allow(dead_code)]
    shutdown: ShutdownToken,
}

impl PacketRouter {
    pub fn new(
        ack_sender: AcknowledgementSender,
        mixnet_message_sender: MixnetMessageSender,
        shutdown: ShutdownToken,
    ) -> Self {
        PacketRouter {
            ack_sender,
            mixnet_message_sender,
            shutdown,
        }
    }

    pub fn route_mixnet_messages(
        &self,
        received_messages: Vec<Vec<u8>>,
    ) -> Result<(), GatewayClientError> {
        if let Err(err) = self.mixnet_message_sender.unbounded_send(received_messages) {
            // PATCHED: Original code panic!()'d here, which triggers Nym SDK global cancellation
            // and kills ALL clients on the runtime. Instead, return a clean error.
            // This commonly happens when a deterministic rendezvous gateway delivers
            // stale messages from a previous session before the receiver task is ready.
            tracing::error!("Failed to send mixnet message (receiver gone): {err}");
            return Err(GatewayClientError::ShutdownInProgress);
        }
        Ok(())
    }

    pub fn route_acks(&self, received_acks: Vec<Vec<u8>>) -> Result<(), GatewayClientError> {
        if let Err(err) = self.ack_sender.unbounded_send(received_acks) {
            // PATCHED: Same fix as route_mixnet_messages above.
            tracing::error!("Failed to send acks (receiver gone): {err}");
            return Err(GatewayClientError::ShutdownInProgress);
        }
        Ok(())
    }
}

impl GatewayPacketRouter for PacketRouter {
    type Error = GatewayClientError;

    // note: this trait tries to decide whether a given message is an ack or a data message

    fn route_mixnet_messages(&self, received_messages: Vec<Vec<u8>>) -> Result<(), Self::Error> {
        self.route_mixnet_messages(received_messages)
    }

    fn route_acks(&self, received_acks: Vec<Vec<u8>>) -> Result<(), Self::Error> {
        self.route_acks(received_acks)
    }
}
