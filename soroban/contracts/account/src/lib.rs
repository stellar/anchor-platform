#![no_std]

use soroban_sdk::{auth::Context, contract, contractimpl, contracttype, Address, BytesN, Env, Vec};

#[contract]
struct Account;

#[derive(Clone)]
#[contracttype]
pub enum DataKey {
    Admin,
}

trait Upgradable {
    fn upgrade(e: Env, new_wasm_hash: BytesN<32>);
}

#[contractimpl]
impl Upgradable for Account {
    fn upgrade(env: Env, new_wasm_hash: BytesN<32>) {
        let admin: Address = env.storage().instance().get(&DataKey::Admin).unwrap();
        admin.require_auth();

        env.deployer().update_current_contract_wasm(new_wasm_hash);
    }
}

#[contractimpl]
impl Account {
    pub fn __constructor(env: Env, admin: Address) {
        env.storage().instance().set(&DataKey::Admin, &admin);
    }

    pub fn __check_auth(
        env: Env,
        signature_payload: BytesN<32>,
        signature: BytesN<64>,
        _auth_context: Vec<Context>,
    ) {
        let public_key: BytesN<32> = env
            .storage()
            .instance()
            .get::<_, BytesN<32>>(&DataKey::Admin)
            .unwrap();
        env.crypto()
            .ed25519_verify(&public_key, &signature_payload.into(), &signature);
    }
}
