(ns kabel-auth.core-test
  (:require [clojure.test :refer :all]
            [kabel-auth.core :refer [p-out-auth inbox-auth auth]]
            [konserve.core :as k]
            [konserve.memory :refer [new-mem-store]]
            [full.async :refer [<? <?? go-try go-loop-try alt?]]
            [clojure.core.async :as async
             :refer [<! >! >!! <!! timeout chan alt! go put!
                     go-loop pub sub unsub close!]]))

;; dummy authentication loop for testing
(sub p-out-auth :loop inbox-auth)

(deftest token-auth
  (testing "Testing token authentication exchange for receiver."
    (let [mapping {:pub/downstream :auth}
          dispatch-fn (fn [m] (or (mapping (:type m)) :unrelated))
          auth-fn (fn [protocol user] (println "Check channel " protocol " for " user))
          in (chan)
          out (chan)
          trusted-connections (atom #{})
          receiver-token-store (<?? (new-mem-store))
          sender-token-store (<?? (new-mem-store))
          [_ [new-in new-out]] (auth trusted-connections
                                     receiver-token-store
                                     sender-token-store
                                     "wss://localhost:8080/some-app/ws"
                                     dispatch-fn
                                     auth-fn
                                     [nil [in out]])]
      #_(go-loop [i (<! new-in)]
          (debug "PASSED:" i)
          (recur (<! new-in)))
      #_(go-loop [o (<! out)]
          (debug "SENDING:" o)
          (recur (<! out)))
      (>!! in {:type :pub/downstream
               :downstream {:foo :bar}
               :user "loop:eve@topiq.es"
               :crdt-id 1
               :peer 4441})
      (is (= (<?? out)
             {:type :kabel-auth.core/auth-request,
              :user "eve@topiq.es",
              :protocol :loop}))
      (is (= (dissoc (<?? out) :token)
             {:type :kabel-auth.core/auth-token}))
      (is (= (<?? new-in)
             {:type :pub/downstream,
              :downstream {:foo :bar},
              :user "loop:eve@topiq.es",
              :crdt-id 1,
              :peer 4441}))
      (>!! in {:type :pub/downstream ;; will pass through with session
               :downstream {:foo :bars}
               :user "loop:eve@topiq.es"
               :crdt-id 1
               :peer 4441})
      (is (= (<?? new-in)
             {:type :pub/downstream,
              :downstream {:foo :bars},
              :user "loop:eve@topiq.es",
              :crdt-id 1,
              :peer 4441})))))
