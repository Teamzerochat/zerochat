export default {
  logo: <strong>ZeroChat</strong>,
  project: {
    link: 'https://github.com/TeamZerochat/zerochat'
  },
  docsRepositoryBase: 'https://github.com/TeamZerochat/zerochat/tree/main/docs-site',
  footer: {
    text: 'ZeroChat Documentation'
  },
  useNextSeoProps() {
    return {
      titleTemplate: '%s - ZeroChat'
    }
  }
}
