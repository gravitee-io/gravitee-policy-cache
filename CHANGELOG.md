## [2.0.3](https://github.com/gravitee-io/gravitee-policy-cache/compare/2.0.2...2.0.3) (2024-04-05)


### Bug Fixes

* **cache:** deep copy of the mutable headers to avoid problems while async store in cache ([fde9bf6](https://github.com/gravitee-io/gravitee-policy-cache/commit/fde9bf64505fa5da8946d10504f18f3e3a50917a))

## [2.0.2](https://github.com/gravitee-io/gravitee-policy-cache/compare/2.0.1...2.0.2) (2023-09-29)


### Bug Fixes

* correct typo in cache bypass instructions ([f6c98b3](https://github.com/gravitee-io/gravitee-policy-cache/commit/f6c98b3d162d15f999b981c6ad5f10a0b6208f8d))

## [2.0.1](https://github.com/gravitee-io/gravitee-policy-cache/compare/2.0.0...2.0.1) (2023-07-20)


### Bug Fixes

* update policy description ([a19677f](https://github.com/gravitee-io/gravitee-policy-cache/commit/a19677f5364dc7d15d4d938316b32ea7db0b1170))

# [2.0.0](https://github.com/gravitee-io/gravitee-policy-cache/compare/1.16.0...2.0.0) (2023-07-18)


### chore

* **deps:** update gravitee-parent ([a8ae21b](https://github.com/gravitee-io/gravitee-policy-cache/commit/a8ae21b8c538ec1ca81392fb498048ba64184f2b))


### Features

* clean and validate json schema for v4 ([bec42c3](https://github.com/gravitee-io/gravitee-policy-cache/commit/bec42c365b98b81dc93dd912c6aa2e191e465047))
* improve special resource type ui component to make it generic ([a140685](https://github.com/gravitee-io/gravitee-policy-cache/commit/a14068540d1903f739fcc8715830cbd63f822563))
* Make cache policy compatible wit V4 API ([38023b2](https://github.com/gravitee-io/gravitee-policy-cache/commit/38023b237dbf67553f0ad2cb3be0e0a5c24a7770))


### BREAKING CHANGES

* **deps:** require Java17
* This implementation is using the dependencies introduced by Gravitee V4.0

# [2.0.0-alpha.3](https://github.com/gravitee-io/gravitee-policy-cache/compare/2.0.0-alpha.2...2.0.0-alpha.3) (2023-06-30)


### Features

* improve special resource type ui component to make it generic ([a140685](https://github.com/gravitee-io/gravitee-policy-cache/commit/a14068540d1903f739fcc8715830cbd63f822563))

# [2.0.0-alpha.2](https://github.com/gravitee-io/gravitee-policy-cache/compare/2.0.0-alpha.1...2.0.0-alpha.2) (2023-06-27)


### Features

* clean and validate json schema for v4 ([bec42c3](https://github.com/gravitee-io/gravitee-policy-cache/commit/bec42c365b98b81dc93dd912c6aa2e191e465047))

# [2.0.0-alpha.1](https://github.com/gravitee-io/gravitee-policy-cache/compare/1.16.0...2.0.0-alpha.1) (2023-06-20)


### Features

* Make cache policy compatible wit V4 API ([38023b2](https://github.com/gravitee-io/gravitee-policy-cache/commit/38023b237dbf67553f0ad2cb3be0e0a5c24a7770))


### BREAKING CHANGES

* This implementation is using the dependencies introduced by Gravitee V4.0

# [1.16.0](https://github.com/gravitee-io/gravitee-policy-cache/compare/1.15.2...1.16.0) (2023-05-05)


### Features

* introduce a binary serialization mode to deal with encoding issue ([1282e8e](https://github.com/gravitee-io/gravitee-policy-cache/commit/1282e8e0abfa88c4eae0be9017986c07de1c306b))

## [1.15.2](https://github.com/gravitee-io/gravitee-policy-cache/compare/1.15.1...1.15.2) (2022-10-12)


### Bug Fixes

* use query parameters when hashing cache key ([d959fc4](https://github.com/gravitee-io/gravitee-policy-cache/commit/d959fc446d30c79ce55fc1658bbe56d203c6e904))

## [1.15.1](https://github.com/gravitee-io/gravitee-policy-cache/compare/1.15.0...1.15.1) (2022-04-28)


### Bug Fixes

* choose the method to cache and add a response condition ([775ad69](https://github.com/gravitee-io/gravitee-policy-cache/commit/775ad6908ab55404d63469027c6bd4a4fd50573e)), closes [gravitee-io/issues#6980](https://github.com/gravitee-io/issues/issues/6980)

# [1.15.0](https://github.com/gravitee-io/gravitee-policy-cache/compare/1.14.0...1.15.0) (2022-02-08)


### Features

* choose the method to cache and add a response condition ([175a21e](https://github.com/gravitee-io/gravitee-policy-cache/commit/175a21ebba83c9cb4c42e4d44dc3a4b2f6f97aa8)), closes [gravitee-io/issues#6980](https://github.com/gravitee-io/issues/issues/6980)

## [1.13.1](https://github.com/gravitee-io/gravitee-policy-cache/compare/1.13.0...1.13.1) (2022-02-08)


### Bug Fixes

* choose the method to cache and add a response condition ([775ad69](https://github.com/gravitee-io/gravitee-policy-cache/commit/775ad6908ab55404d63469027c6bd4a4fd50573e)), closes [gravitee-io/issues#6980](https://github.com/gravitee-io/issues/issues/6980)

# [1.14.0](https://github.com/gravitee-io/gravitee-policy-cache/compare/1.13.0...1.14.0) (2022-01-21)


### Features

* **headers:** Internal rework and introduce HTTP Headers API ([c485c5f](https://github.com/gravitee-io/gravitee-policy-cache/commit/c485c5ff9a5d6f550ed816f1387bfb3dc0c80cf3)), closes [gravitee-io/issues#6772](https://github.com/gravitee-io/issues/issues/6772)
