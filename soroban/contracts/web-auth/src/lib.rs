#![no_std]

use soroban_sdk::{
    contract, contracterror, contractimpl, contractmeta, contracttype, Address, BytesN, Env, Map, String, Symbol
};

contractmeta!(key="sep", val="45");
contractmeta!(key="version", val="0.1.0");

#[contract]
pub struct WebAuthContract;

#[derive(Clone)]
#[contracttype]
pub enum DataKey {
    Admin,
}

trait Upgradable {
    fn upgrade(e: Env, new_wasm_hash: BytesN<32>);
}

#[contractimpl]
impl Upgradable for WebAuthContract {
    fn upgrade(env: Env, new_wasm_hash: BytesN<32>) {
        let admin: Address = env.storage().instance().get(&DataKey::Admin).unwrap();
        admin.require_auth();

        env.deployer().update_current_contract_wasm(new_wasm_hash);
    }
}

#[contracterror]
pub enum WebAuthError {
    MissingArgument = 1,
}

#[contractimpl]
impl WebAuthContract {
    pub fn __constructor(env: Env, admin: Address) {
        env.storage().instance().set(&DataKey::Admin, &admin);
    }

    pub fn web_auth_verify(env: Env, args: Map<Symbol, String>) -> Result<(), WebAuthError> {
        if let Some(address) = args.get(Symbol::new(&env, "account")) {
            let addr = Address::from_string(&address);
            addr.require_auth();
        } else {
            return Err(WebAuthError::MissingArgument);
        }

        if let Some(web_auth_domain_account) = args.get(Symbol::new(&env, "web_auth_domain_account")) {
            let addr = Address::from_string(&web_auth_domain_account);
            addr.require_auth();
        } else {
            return Err(WebAuthError::MissingArgument);
        }

        if let Some(client_domain_account) = args.get(Symbol::new(&env, "client_domain_account")) {
            let addr = Address::from_string(&client_domain_account);
            addr.require_auth();
        }

        Ok(())
    }
}
