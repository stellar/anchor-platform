#!/usr/bin/env bash
set -euo pipefail

SUITES="Infrastructure Sep10 Sep12 Sep38 Sep6 Sep24 Sep31 Sep45 Platform"
RPC="Platform Sep6 Sep24 Sep31 Sep38"

RULES=(
  "^(docs|helm-charts|extended-tests)/|\.md$|/src/test/kotlin/|^(LICENSE|icon\.png)$ =>"
  "^essential-tests/.*/((ServerHealth|Kafka|LedgerClient|Sep1|Sep1TomlRules|Cors)Tests|EventDeliveryProbeTest)\.kt$ => Infrastructure"
  "^essential-tests/.*/(Callback[A-Za-z]*|ClientConfigApiTests|EventProcessingTests|MultiClientCallbackTests|PaymentObserverTests|StellarObserverTests|WalletCallbackRecordTests|PlatformApiTests)\.kt$ => Platform"
  "[Ss]ep6[/A-Z] => Sep6 Sep38 Platform"
  "[Ss]ep(12|9)[/A-Z]|callbacks/customer/|RestCustomerIntegration|NotifyCustomerInfoUpdated => Sep12 Sep6 Sep24 Sep31 Sep38 Platform"
  "[Ss]ep24[/A-Z]|callbacks/interactive/|NotifyInteractiveFlowCompleted => Sep24 Sep38 Platform"
  "[Ss]ep31[/A-Z] => Sep31 Sep38 Platform"
  "[Ss]ep38[/A-Z]|callbacks/rate/|RestRateIntegration|GetQuoteHandler => Sep38 Sep6 Sep24 Sep31 Platform"
  "[Ss]ep45[/A-Z] => Sep45 Sep6 Sep24"
  "^platform/.*/platform/(rpc|observer|callback|controller/platform)/|^api-schema/.*/api/(rpc|platform|callback)/ => $RPC"
)

EXTENDED=(
  "AuthApikey <= Platform"
  "AuthJwt <= Platform"
  "ClientsDb <= Sep10"
  "End2EndHorizon <= Sep6 Sep24 Sep31"
)

EXTENDED_JOBS=(
  "AuthAndClients <= AuthApikey AuthJwt ClientsDb"
  "End2EndHorizon <= End2EndHorizon"
)

suites_for() {
  local rule

  for rule in "${RULES[@]}"; do
    if [[ $1 =~ ${rule%% =>*} ]]; then
      hit=${rule#*=>}

      return
    fi
  done

  hit=$SUITES
}

select_suites() {
  local picked="" out="" f s

  while read -r f; do
    suites_for "$f"
    picked="$picked $hit"

  done < <(git diff --no-renames --name-only "$(git merge-base "$1" HEAD)" HEAD)

  for s in $SUITES; do
    if [[ " $picked " == *" $s "* ]]; then out="$out $s"; fi
  done

  echo "${out# }"
}

select_extended() {
  local changed essential out="" entry s

  changed=$(git diff --no-renames --name-only "$(git merge-base "$1" HEAD)" HEAD)

  if grep -q '^extended-tests/' <<< "$changed"; then
    essential=$SUITES
  else
    essential=$(select_suites "$1")
  fi

  for entry in "${EXTENDED[@]}"; do
    for s in ${entry#*<= }; do
      if [[ " $essential " == *" $s "* ]]; then out="$out ${entry%% <=*}"; break; fi
    done
  done

  echo "${out# }"
}

fingerprints() {
  local tmp meta path s entry

  tmp=$(mktemp -d)

  for s in $SUITES; do : > "$tmp/$s"; done

  while IFS=$'\t' read -r meta path; do
    suites_for "$path"

    for s in $hit; do echo "$meta $path" >> "$tmp/$s"; done
  done < <(git ls-files -s)

  for s in $SUITES; do echo "$s $(sha256sum < "$tmp/$s" | cut -c1-16)"; done > "$tmp/all"

  cat "$tmp/all"

  echo "extended-tests $(git ls-files -s extended-tests | sha256sum | cut -c1-16)" >> "$tmp/all"

  for entry in "${EXTENDED[@]}"; do
    echo "${entry%% <=*} $(for s in ${entry#*<= } extended-tests; do grep "^$s " "$tmp/all"; done | sha256sum | cut -c1-16)"
  done

  rm -rf "$tmp"
}

check_names() {
  local s

  for s in ${*:2}; do
    if [[ " $1 " != *" $s "* ]]; then echo "Unknown suite: $s" >&2; return 1; fi
  done
}

essential_matrix() {
  local fps entries="" s

  if [ "$*" = all ]; then set -- $SUITES; fi

  check_names "$SUITES" "$@"

  fps=$(fingerprints)

  for s in "$@"; do
    entries="$entries{\"suite\":\"$s\",\"fingerprint\":\"$(grep "^$s " <<< "$fps" | cut -d' ' -f2)\"},"
  done

  echo "matrix=[${entries%,}]"
}

extended_matrix() {
  local fps entries="" names="" job members s

  for job in "${EXTENDED[@]}"; do names="$names ${job%% <=*}"; done

  if [ "$*" = all ]; then set -- $names; fi

  check_names "$names" "$@"

  fps=$(fingerprints)

  for job in "${EXTENDED_JOBS[@]}"; do
    members=""

    for s in ${job#*<= }; do
      if [[ " $* " == *" $s "* ]]; then members="$members $s"; fi
    done

    if [ -z "$members" ]; then continue; fi

    entries="$entries{\"group\":\"${job%% <=*}\",\"suites\":\"${members# }\",\"fingerprint\":\"$(for s in $members; do grep "^$s " <<< "$fps"; done | sha256sum | cut -c1-16)\"},"
  done

  echo "matrix=[${entries%,}]"
}

matrix() {
  local names="" essential="" extended="" job s

  for job in "${EXTENDED[@]}"; do names="$names ${job%% <=*}"; done

  if [ "$*" = all ]; then set -- $SUITES $names; fi

  check_names "$SUITES $names" "$@"

  for s in "$@"; do
    if [[ " $names " == *" $s "* ]]; then extended="$extended $s"; else essential="$essential $s"; fi
  done

  essential_matrix $essential | sed 's/^matrix=/essential=/'
  extended_matrix $extended | sed 's/^matrix=/extended=/'
}

case "${1:-}" in
  select) select_suites "$2" ;;

  select-extended) select_extended "$2" ;;

  fingerprints) fingerprints ;;

  matrix) matrix "${@:2}" ;;
  
  *) echo "usage: essential-suites.sh select|select-extended <base> | matrix <suites...|all> | fingerprints" >&2; exit 1 ;;
esac
