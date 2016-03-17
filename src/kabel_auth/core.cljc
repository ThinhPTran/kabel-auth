(ns kabel-auth.core
  "Authentication middleware for kabel."
  (:require [kabel.platform-log :refer [debug info warn error]]
            [konserve.core :as k]
            [hasch.core :refer [uuid]]
            [full.async :refer [<? <?? go-try go-loop-try alt?]]
            #?(:clj [clojure.core.async :as async
                     :refer [<! >! >!! <!! timeout chan alt! go put!
                             go-loop pub sub unsub close!]]
               :cljs [cljs.core.async :as async
                      :refer [<! >! timeout chan put! pub sub unsub close!]])))

;; next steps
;; - make timeouts configurable
;; - IO
;;   - provide mailer for urls with token on out-auth
;;     community auth relay?
;;   - collect tokens activated on in-auth

(defn now [] #?(:clj (java.util.Date.)
                :cljs (js/Date.)))


(def external-tokens (atom {}))

(defn register-external-token
  "Use this function to create a token to communicate externally,
  e.g. exposed in an authenticating URL clicked by the user. You need
  to map this back via the external-tokens atom."
  [token]
  (let [ext-token (uuid token)]
    (swap! external-tokens assoc ext-token token)
    ext-token))

(def inbox-auth (chan))
(def ^:private p-in-auth (pub inbox-auth :token))

(def ^:private outbox-auth (chan))
(def p-out-auth (pub outbox-auth :protocol))



;; ===== receiver side =====

(defn auth-request [receiver-token-store peer user session-id out]
  (let [[[_ proto username]] (re-seq #"(.+):(.+)" user)
        token (uuid)
        a-ch (chan)]
    (sub p-in-auth token a-ch)
    (go-try
     (debug "requesting auth" user)
     (>! out {:type ::auth-request :user username :protocol (keyword proto)})
     (>! outbox-auth {:token token :user username :protocol (keyword proto)})
     (alt? a-ch
           (let [tok {:token token :time (now) :session session-id}]
             (debug "authenticated" user token)
             (>! out {:type ::auth-token :token token})
             (<! (k/assoc-in receiver-token-store [peer user] tok)))

           (timeout (* 5 60 1000))
           (debug "timeout" user)))))

(defn authenticate [trusted-connections receiver-token-store auth-ch new-in out]
  (let [session-id (uuid)]
    (go-loop-try []
                 (let [{:keys [peer connection downstream user] msg-token :token :as a-msg} (<? auth-ch)
                       token-timeout (* 10 60 1000)]
                   (when a-msg
                     (debug "authenticating" user)
                     (cond  (@trusted-connections connection)
                            (>! new-in a-msg)

                            (let [{:keys [time token session]}
                                  (<? (k/get-in receiver-token-store [peer user]))]
                              (debug "token exists?" peer user token)
                              (or (= session-id session) ;; already authed this user in this session
                                  (and msg-token
                                       (= msg-token token)
                                       (< (- (.getTime (now))
                                             (.getTime time))
                                          token-timeout))))
                            (do (debug "msg token is valid" msg-token)
                                (>! new-in a-msg))

                            (<? (auth-request receiver-token-store peer user session-id out))
                            (>! new-in a-msg)

                            :default
                            (>! out {:type ::auth-timeout :msg a-msg}))
                     (recur))))))

;; ===== sender side =====
(defn store-token [token-store store-token-ch]
  (go-loop-try [{:keys [user token connection]} (<? store-token-ch)]
               (when token
                 (<? (k/update-in token-store [connection user] token))
                 (recur (<? store-token-ch)))))

(defn add-tokens-to-out [remote sender-token-store out new-out]
  (go-loop-try [o (<? new-out)]
               (when o
                 (>! out (if-let [t (when (:user o) ;; TODO user hardcoded
                                      (<? (k/get-in sender-token-store [remote (:user o)])))]
                           (assoc o :token t)
                           o))
                 (recur (<? new-out)))))

(defn auth-reply [auth-request-ch auth-fn out]
  (go-loop-try [{:keys [user protocol]} (<? auth-request-ch)]
               (when user
                 (<? (auth-fn protocol user))
                 (recur (<? auth-request-ch)))))


;; one sender-store per connection
;; m sender-stores with tokens map to receiver-store, mapped by peer-id (TODO can disturb auth?)
(defn auth [trusted-connections
            receiver-token-store
            sender-token-store
            connection
            dispatch-fn
            auth-fn
            [peer [in out]]]
  (let [new-in (chan)
        new-out (chan)
        p (pub in (fn [{:keys [type] :as m}]
                    (case type
                      ;; sender
                      ::auth-request ::auth-request
                      ::auth-token ::auth-token
                      (dispatch-fn m))))
        auth-ch (chan)
        auth-request-ch (chan)
        store-token-ch (chan)]
    ;; receiver
    (sub p :auth auth-ch)
    (authenticate trusted-connections receiver-token-store auth-ch new-in out)


    ;; sender
    (sub p ::auth-request auth-request-ch)
    (auth-reply auth-request-ch auth-fn out)

    (sub p ::auth-token store-token-ch)
    (store-token sender-token-store store-token-ch)

    (add-tokens-to-out connection sender-token-store out new-out)


    (sub p :unrelated new-in) ;; pass-through
    [peer [new-in new-out]]))
