(ns kabel-auth.core
  "Authentication middleware for kabel."
  (:require [kabel.platform-log :refer [debug info warn error]]
            [konserve.core :as k]
            [hasch.core :refer [uuid]]
            #?(:clj [full.async :refer [<? <?? go-try go-loop-try alt?]])
            #?(:clj [clojure.core.async :as async
                     :refer [<! >! >!! <!! timeout chan alt! go put!
                             go-loop pub sub unsub close!]]
               :cljs [cljs.core.async :as async
                      :refer [<! >! timeout chan put! pub sub unsub close!]]))
  #?(:cljs (:require-macros [full.cljs.async :refer [<<? <? go-for go-try go-try> go-loop-try go-loop-try> alt?]])))

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


;; ===== receiver side =====

(defn auth-request [receiver-token-store sender user session-id
                    request-fn out new-in a-msg request-timeout]
  (let [[[_ proto username]] (re-seq #"(.+):(.+)" user)
        token (uuid)
        a-ch (chan)]
    (sub p-in-auth token a-ch)
    (go-try
     (debug "requesting auth" user "with timeout" request-timeout)
     (>! out {:type ::auth-request :user username :protocol (keyword proto)})
     (request-fn {:token token :user username :protocol (keyword proto)})
     (alt? a-ch
           (let [tok {:token token :time (now) :session session-id}]
             (debug "authenticated" user token)
             (>! out {:type ::auth-token :token token :user user})
             (<! (k/assoc-in receiver-token-store [sender user] tok))
             (>! new-in a-msg))

           (timeout request-timeout)
           (do
             (debug "timeout" user)
             (>! out {:type ::auth-timeout :msg a-msg}))))))



(defn authenticate [trusted-hosts receiver-token-store
                    request-fn auth-ch new-in out token-timeout request-timeout]
  (let [session-id (uuid)]
    (go-loop-try []
                 (let [{:keys [sender host downstream user] msg-token :token :as a-msg}
                       (<? auth-ch)]
                   (when a-msg
                     (debug "authenticating" user "for" host)
                     (cond  (@trusted-hosts host)
                            (do (debug "trusted host" host)
                                (>! new-in a-msg))

                            (let [{:keys [time token session]}
                                  (<? (k/get-in receiver-token-store [sender user]))]
                              (debug "token exists?" sender user token " msg-token: " msg-token)
                              (or (= session-id session) ;; already authed this user in this session
                                  (and msg-token
                                       (= msg-token token)
                                       (< (- (.getTime (now))
                                             (.getTime time))
                                          token-timeout))))
                            (do (debug "msg token is valid" msg-token)
                                (>! new-in a-msg))

                            :default
                            (auth-request receiver-token-store sender user session-id
                                          request-fn out new-in a-msg request-timeout))
                     (recur))))))

;; ===== sender side =====
(defn store-token [token-store store-token-ch]
  (go-loop-try [{:keys [user token host]} (<? store-token-ch)]
               (when token
                 (<? (k/assoc-in token-store [host user] token))
                 (recur (<? store-token-ch)))))

(defn add-tokens-to-out [remote sender-token-store out new-out]
  (go-loop-try [o (<? new-out)]
               (when o
                 (>! out (if-let [t (when (:user o) ;; TODO user hardcoded
                                      (<? (k/get-in sender-token-store [@remote (:user o)])))]
                           (assoc o :token t)
                           o))
                 (recur (<? new-out)))))

(defn auth-reply [auth-request-ch auth-fn out]
  (go-loop-try [{:keys [user protocol]} (<? auth-request-ch)]
               (when user
                 (<? (auth-fn protocol user))
                 (recur (<? auth-request-ch)))))


;; one sender-store per host
;; m sender-stores with tokens map to receiver-store, mapped by peer-id (TODO can disturb auth?)
(defn auth [trusted-hosts
            receiver-token-store
            sender-token-store
            dispatch-fn
            auth-fn
            request-fn
            [peer [in out]]
            & {:keys [token-timeout request-timeout msg->user]
               :or {token-timeout (* 31 24 60 60 1000)
                    request-timeout (* 5 60 1000)}}]
  (let [new-in (chan)
        new-out (chan)
        remote (atom nil)
        p (pub in (fn [{:keys [type host] :as m}]
                    ;; TODO uglily taken from first message coming in
                    (when-not @remote (reset! remote host))
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
    (authenticate trusted-hosts receiver-token-store request-fn auth-ch new-in out token-timeout request-timeout)


    ;; sender
    (sub p ::auth-request auth-request-ch)
    (auth-reply auth-request-ch auth-fn out)

    (sub p ::auth-token store-token-ch)
    (store-token sender-token-store store-token-ch)

    (add-tokens-to-out remote sender-token-store out new-out)


    (sub p :unrelated new-in) ;; pass-through
    [peer [new-in new-out]]))
