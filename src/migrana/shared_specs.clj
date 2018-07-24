(ns migrana.shared-specs
  (:require [clojure.spec.alpha :as s]))

(s/def ::server-type #{:ion})
(s/def ::region #{"us-east-1"
                  "us-east-2"
                  "us-west-1"
                  "us-west-2"
                  "ca-central-1"
                  "eu-central-1"
                  "eu-west-1"
                  "eu-west-2"
                  "eu-west-3"
                  "ap-northeast-1"
                  "ap-northeast-2"
                  "ap-northeast-3"
                  "ap-southeast-1"
                  "ap-southeast-2"
                  "ap-south-1"
                  "sa-east-1"})
(s/def ::system string?)
(s/def ::query-group string?)
(s/def ::endpoint string?)
(s/def ::proxy-port integer?)

(s/def ::cfg (s/keys :req-un [::server-type
                              ::region
                              ::system
                              ::query-group
                              ::endpoint
                              ::proxy-port]))

(s/def ::db-name string?)

(s/def ::timestamp #"[0-9]{14,14}")
