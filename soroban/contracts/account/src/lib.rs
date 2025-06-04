#![no_std]

use soroban_sdk::{
    auth::{Context, CustomAccountInterface},
    contract, contracterror, contractimpl, contracttype,
    crypto::Hash,
    Address, BytesN, Env, Vec,
};

#[contract]
struct Account;

#[derive(Clone)]
#[contracttype]
pub enum DataKey {
    Admin,
    Signer(BytesN<32>),
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

#[contracttype]
#[derive(Clone)]
pub struct Signature {
    pub public_key: BytesN<32>,
    pub signature: BytesN<64>,
}

#[contracterror]
#[derive(Clone)]
pub enum AccountError {
    UnknownSigner = 1,
    TooManySignatures = 2,
}

#[contractimpl]
impl Account {
    pub fn __constructor(env: Env, admin: Address, signer: BytesN<32>) {
        env.storage().instance().set(&DataKey::Admin, &admin);
        env.storage().instance().set(&DataKey::Signer(signer), &());
    }
}

#[contractimpl]
impl CustomAccountInterface for Account {
    type Error = AccountError;
    type Signature = Vec<Signature>;

    fn __check_auth(
        env: Env,
        signature_payload: Hash<32>,
        signatures: Self::Signature,
        _auth_context: Vec<Context>,
    ) -> Result<(), AccountError> {
        if signatures.len() > 1 {
            return Err(AccountError::TooManySignatures);
        }

        let signature = signatures.get_unchecked(0);

        if env
            .storage()
            .instance()
            .get::<_, ()>(&DataKey::Signer(signature.public_key.clone()))
            .is_none()
        {
            return Err(AccountError::UnknownSigner);
        }

        env.crypto().ed25519_verify(
            &signature.public_key,
            &signature_payload.into(),
            &signature.signature,
        );

        Ok(())
    }
}
