fn main() {
    uniffi::generate_scaffolding("src/nym_transport.udl")
        .expect("Failed to generate UniFFI scaffolding from UDL");
}
