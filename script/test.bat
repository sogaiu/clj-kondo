@echo off

rem populate mvn cache for extract tests
clojure -Sdeps '{:deps {org.clojure/clojure {:mvn/version "1.9.0"}}}' -Spath > /dev/null
clojure -Sdeps '{:deps {org.clojure/clojure {:mvn/version "1.10.1"}}}' -Spath > /dev/null
clojure -Sdeps '{:deps {org.clojure/clojurescript {:mvn/version "1.10.520"}}}' -Spath > /dev/null

if "%CLJ_KONDO_TEST_ENV%"=="native" (
  clojure -A:test
)
else (
  echo "Testing with Clojure 1.9.0"
  clojure -A:clojure-1.9.0:test
  lein with-profiles +clojure-1.9.0 do clean, test

  echo "Testing with Clojure 1.10.1"
  clojure -A:clojure-1.10.1:test
  lein with-profiles +clojure-1.10.1 do clean, test
)
