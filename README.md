# kabel-auth

This is an authentication middleware for
[kabel](https://github.com/replikativ/kabel). It is implemented according to [passwordless authentication](https://medium.com/@ninjudd/passwords-are-obsolete-9ed56d483eb):

> Here’s how passwordless authentication works in more detail:

>     Instead of asking users for a password when they try to log in
>     to your app or website, just ask them for their username (or
>     email or mobile phone number).  Create a temporary
>     authorization code on the backend receiver and store it in your
>     database.  Send the user an email or SMS with a link that
>     contains the code.  The user clicks the link which opens your
>     app or website and sends the authorization code to your receiver.
>     On your backend receiver, verify that the code is valid and
>     exchange it for a long-lived token, which is stored in your
>     database and sent back to be stored on the sender device as
>     well.  The user is now logged in, and doesn’t have to repeat
>     this process again until their token expires or they want to
>     authenticate on a new device.

It is used in [replikativ](https://github.com/replikativ/replikativ)
to build a p2p network. The middleware is symmetric, so both sides
need to authenticate each other. There are two levels of
authentication. One is a trust based one where you can whitelist
connections (e.g. classical clients receiving messages from a trusted
server) to other peers from kabel. The other is the passwordless
authentication over a secondary channel. We provide a secondary
channel for e-mail + url atm., feel free to extend it to new
providers.


Note that this also allows to implement password authentication by
using the same kabel channels to request the password, so the
secondary channel is then the primary one.

## Usage

Include in your dependencies:
[![Clojars Project](http://clojars.org/io.replikativ/kabel-auth/latest-version.svg)](http://clojars.org/io.replikativ/kabel-auth)

You can instantiate the middleware like this:

~~~clojure
(require '[kabel-auth.core :refer [auth inbox-auth register-external-token external-tokens]]
         '[postal.core :refer [send-message]])

(auth (atom #{"trusted-peer.com" "localhost" "127.0.0.1"})
      receiver-token-store ;; some (dedicated) konserve store
      sender-token-store ;; some (dedicated) konserve store
      ;; decide which messages need protection
      (fn [{:keys [type]}] (or ({:state-changing-msg-type :auth} type)
                               :unrelated))
      ;; notification when authentication is needed
      (fn [protocol user] (alert! "Check channel " protocol " for " user))
      ;; provide an authentication notifier
      (fn [{:keys [protocol token user]}] ;; only for :mail protocol here
        (let [ext-tok (register-external-token token)]
          (send-message {:host "smtp.your-host.com"}
                        {:from "no-reply@your-host.com"
                         :to user
                         :subject "Please authenticate"
                         :body (str "Visit http://your-end-point/auth/" ext-tok)})))
      [peer [in out]])
~~~

Furthermore you have to provide an end-point for authentication:

~~~clojure
(routes ;; your compojure routes
  (GET "/auth/:token" [token]
    (put! inbox-auth {:token (@external-tokens (java.util.UUID/fromString token))})))
~~~

A full example can be found [here](https://github.com/whilo/topiq/blob/master/src/topiq/core.cljs).

At the moment you need to provide the user to authenticate for under
the key `:user` in each relevant message. This is sufficient for
[replikativ](https://github.com/replikativ/replikativ), but might not
fit your design. If you want to flexibly map user identities to
messages, please open an issue.


## Roadmap
   - add public/private key authentication of signed messages as a third level

## License

Copyright © 2016 Christian Weilbach

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
